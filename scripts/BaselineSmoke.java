import com.chat.ChatRoom;
import com.chat.proto.ChatProto.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Stage-0 probe, deliberately separate from the future regression test suite. */
public class BaselineSmoke {
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    static Process server;
    static Path directory;

    static class Client implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<ServerResponse> responses = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        volatile Throwable failure;
        WebSocket socket;
        Client() {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:8080/ws"), this).join();
        }
        public void onOpen(WebSocket ws) { ws.request(1); }
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer bytes, boolean last) {
            byte[] chunk = new byte[bytes.remaining()]; bytes.get(chunk);
            fragments.writeBytes(chunk);
            if (last) {
                try { responses.add(ServerResponse.parseFrom(fragments.toByteArray())); }
                catch (Exception e) { failure = e; }
                fragments.reset();
            }
            ws.request(1); return null;
        }
        public void onError(WebSocket ws, Throwable error) { failure = error; }
        void send(ClientRequest request) {
            socket.sendBinary(ByteBuffer.wrap(request.toByteArray()), true).join();
        }
        ServerResponse await(Predicate<ServerResponse> condition) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end) {
                if (failure != null) throw new AssertionError("WebSocket failure", failure);
                ServerResponse response = responses.poll(100, TimeUnit.MILLISECONDS);
                if (response != null && condition.test(response)) return response;
            }
            throw new AssertionError("Timed out waiting for response");
        }
        ServerResponse join(String name) throws Exception {
            send(ClientRequest.newBuilder().setType(ClientRequest.RequestType.JOIN)
                .setJoin(JoinRequest.newBuilder().setName(name)).build());
            return await(r -> r.getType() == ServerResponse.ResponseType.JOIN_SUCCESS
                || r.getType() == ServerResponse.ResponseType.JOIN_ERROR);
        }
        public void close() { socket.abort(); }
    }
    static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        System.out.println("PASS: " + description);
    }
    static void start(Path jar) throws Exception {
        server = new ProcessBuilder(JAVA, "-jar", jar.toString()).directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("server.log").toFile())).start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (!server.isAlive()) throw new AssertionError("Server exited; inspect " + directory);
            try (Socket connection = new Socket()) {
                connection.connect(new InetSocketAddress("localhost",8080), 200); return;
            } catch (java.io.IOException e) { Thread.sleep(100); }
        }
        throw new AssertionError("Server startup timed out");
    }
    static void stop() throws Exception {
        if (server == null) return;
        server.destroy();
        if (!server.waitFor(10, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the built server JAR path");
        Path jar = Path.of(args[0]).toAbsolutePath();
        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("Build server first: " + jar);
        try (java.net.ServerSocket guard = new java.net.ServerSocket()) {
            guard.setReuseAddress(true); guard.bind(new InetSocketAddress(8080));
        }
        directory = Files.createTempDirectory("messaging-baseline-");
        System.out.println("Temporary synthetic history and logs: " + directory);
        try {
            start(jar);
            try (Client alice = new Client(); Client bob = new Client(); Client observer = new Client(); Client duplicate = new Client()) {
                check(alice.join("Alice").hasJoinSuccess(), "Alice joins");
                check(bob.join("Bob").hasJoinSuccess(), "Bob joins");
                check(observer.join("Observer").hasJoinSuccess(), "Observer joins");
                check(duplicate.join("Alice").getType() == ServerResponse.ResponseType.JOIN_ERROR, "Duplicate name rejected");
                alice.send(ClientRequest.newBuilder().setType(ClientRequest.RequestType.CHAT)
                    .setChat(ChatRequest.newBuilder().setText("baseline hello")).build());
                for (Client client : new Client[]{alice,bob,observer}) {
                    check(client.await(r -> r.hasBroadcast()).getBroadcast().getText().equals("baseline hello"), "Public message delivered");
                }
                alice.send(ClientRequest.newBuilder().setType(ClientRequest.RequestType.PRIVATE_CHAT)
                    .setPrivateChat(PrivateChatRequest.newBuilder().setTargetName("Bob").setText("private hello")).build());
                check(alice.await(r -> r.hasPrivateMsg()).getPrivateMsg().getText().equals("private hello"), "PM echoed to sender");
                check(bob.await(r -> r.hasPrivateMsg()).getPrivateMsg().getText().equals("private hello"), "PM delivered to recipient");
                // Observer's own public message is an ordering barrier on the same event loop.
                observer.send(ClientRequest.newBuilder().setType(ClientRequest.RequestType.CHAT)
                    .setChat(ChatRequest.newBuilder().setText("observer barrier")).build());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean barrier = false;
                while (System.nanoTime() < deadline) {
                    ServerResponse r = observer.responses.poll(100, TimeUnit.MILLISECONDS);
                    if (r == null) continue;
                    if (r.hasPrivateMsg()) throw new AssertionError("PM leaked to observer");
                    if (r.hasBroadcast() && r.getBroadcast().getText().equals("observer barrier")) { barrier = true; break; }
                }
                check(barrier, "Observer received barrier without private message");
                alice.send(ClientRequest.newBuilder().setType(ClientRequest.RequestType.PRIVATE_CHAT)
                    .setPrivateChat(PrivateChatRequest.newBuilder().setTargetName("Missing").setText("hello")).build());
                check(alice.await(r -> r.hasError()).getError().getErrorCode() == 404, "Unknown recipient returns 404");
            }
            stop(); start(jar);
            try (Client reader = new Client()) {
                JoinSuccessResponse restored = reader.join("Reader").getJoinSuccess();
                check(restored.getHistoryCount() == 2, "Two public messages survive restart; PM absent");
                check(restored.getHistory(0).getText().equals("baseline hello"), "Restored history content matches");
            }
            // Isolated state probe documents the known defect; it must change during stage 1.
            ChatRoom room = new ChatRoom();
            EmbeddedChannel channel = new EmbeddedChannel();
            try {
                room.join(channel,"OldName",null,""); room.join(channel,"NewName",null,""); room.leave(channel);
                check(room.getActiveUserCount() == 0 && room.isUserOnline("OldName"), "KNOWN DEFECT reproduced: repeated JOIN leaves stale name");
            } finally { channel.finishAndReleaseAll(); }
            System.out.println("BASELINE SMOKE PASSED (including one expected known defect)");
        } finally { stop(); }
    }
}
