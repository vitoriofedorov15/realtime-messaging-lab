package com.chat;

import com.chat.proto.ChatProto.*;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ChatRegressionTest {
    @TempDir Path directory;
    ChatRoom room;
    List<EmbeddedChannel> channels;
    @BeforeEach void setup() { room = new ChatRoom(directory.resolve("history.bin").toFile()); channels = new ArrayList<>(); }
    @AfterEach void cleanup() { channels.forEach(EmbeddedChannel::finishAndReleaseAll); }
    EmbeddedChannel client() {
        var ch = new EmbeddedChannel(new WebSocketHandler(room)); channels.add(ch); return ch;
    }
    List<ServerResponse> drain(EmbeddedChannel ch) throws Exception {
        List<ServerResponse> out = new ArrayList<>(); BinaryWebSocketFrame frame;
        while ((frame = ch.readOutbound()) != null) {
            try { byte[] bytes = new byte[frame.content().readableBytes()]; frame.content().readBytes(bytes); out.add(ServerResponse.parseFrom(bytes)); }
            finally { frame.release(); }
        }
        return out;
    }
    ServerResponse send(EmbeddedChannel ch, ClientRequest request) throws Exception {
        ch.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(request.toByteArray())));
        var responses = drain(ch); assertEquals(1, responses.size(), "Expected one direct response"); return responses.get(0);
    }
    ClientRequest join(String name) { return ClientRequest.newBuilder().setType(ClientRequest.RequestType.JOIN).setJoin(JoinRequest.newBuilder().setName(name)).build(); }
    ClientRequest chat(String text) { return ClientRequest.newBuilder().setType(ClientRequest.RequestType.CHAT).setChat(ChatRequest.newBuilder().setText(text)).build(); }
    ClientRequest pm(String target, String text) { return ClientRequest.newBuilder().setType(ClientRequest.RequestType.PRIVATE_CHAT).setPrivateChat(PrivateChatRequest.newBuilder().setTargetName(target).setText(text)).build(); }
    EmbeddedChannel joined(String name) throws Exception {
        var ch = client(); assertTrue(send(ch,join(name)).hasJoinSuccess());
        for (var other : channels) drain(other); return ch;
    }
    static byte[] picture(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),format,output)); return output.toByteArray();
    }
    @Test void repeatedJoinDoesNotRenameOrLeakName() throws Exception {
        var a = joined("Alice"); var result = send(a,join("Bob"));
        assertEquals(409,result.getError().getErrorCode()); assertTrue(result.getError().getErrorMessage().contains("ALREADY_JOINED"));
        assertEquals("Alice",room.getUserName(a)); assertNull(room.getChannelByName("Bob"));
        a.close(); assertFalse(room.isUserOnline("Alice")); assertEquals(0,room.getActiveUserCount());
        assertTrue(send(client(),join("Alice")).hasJoinSuccess());
    }
    @Test void duplicateAndInvalidNamesAreRejected() throws Exception {
        joined("Alice"); var other = client();
        assertEquals(409,send(other,join("Alice")).getError().getErrorCode());
        for (String name : List.of("", "a b", "<script>", "a".repeat(51), "😀")) {
            assertEquals(ServerResponse.ResponseType.JOIN_ERROR,send(other,join(name)).getType());
        }
        assertTrue(send(other,join("  Виктор_1-  ")).hasJoinSuccess());
    }
    @Test void unicodeNameBoundary() throws Exception {
        String supplementaryLetter = new String(Character.toChars(0x10400));
        assertTrue(send(client(),join(supplementaryLetter.repeat(50))).hasJoinSuccess());
        assertEquals(400,send(client(),join(supplementaryLetter.repeat(51))).getError().getErrorCode());
    }
    @Test void concurrentSameNameHasExactlyOneWinner() throws Exception {
        var a = client(); var b = client(); var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            Future<ChatRoom.JoinResult> first = pool.submit(() -> {gate.await(); return room.join(a,"Same",null,"");});
            Future<ChatRoom.JoinResult> second = pool.submit(() -> {gate.await(); return room.join(b,"Same",null,"");});
            gate.countDown();
            assertEquals(Set.of(ChatRoom.JoinResult.OK,ChatRoom.JoinResult.NAME_TAKEN),Set.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)));
            assertEquals(1,room.getActiveUserCount());
        } finally { pool.shutdownNow(); }
    }
    @Test void capacityAndReleasedSlot() {
        for (int i=0;i<100;i++) assertEquals(ChatRoom.JoinResult.OK,room.join(client(),"u"+i,null,""));
        var overflow=client(); assertEquals(ChatRoom.JoinResult.ROOM_FULL,room.join(overflow,"extra",null,""));
        room.leave(channels.get(0)); assertEquals(ChatRoom.JoinResult.OK,room.join(overflow,"extra",null,""));
    }
    @Test void messagesRequireJoinAndPayload() throws Exception {
        var a=client(); assertEquals(401,send(a,chat("hi")).getError().getErrorCode());
        assertEquals(401,send(a,pm("Bob","hi")).getError().getErrorCode());
        assertEquals(400,send(a,ClientRequest.newBuilder().setType(ClientRequest.RequestType.JOIN).build()).getError().getErrorCode());
        send(a,join("Alice"));
        for (var type: List.of(ClientRequest.RequestType.CHAT,ClientRequest.RequestType.PRIVATE_CHAT))
            assertEquals(400,send(a,ClientRequest.newBuilder().setType(type).build()).getError().getErrorCode());
    }
    @Test void textBoundariesAreSameForPublicAndPrivate() throws Exception {
        var a=joined("Alice");
        for (String text:List.of("", " \t\n", "\u00a0", "a".repeat(26), "😀".repeat(26))) {
            assertEquals(400,send(a,chat(text)).getError().getErrorCode());
            assertEquals(400,send(a,pm("Alice",text)).getError().getErrorCode());
        }
        for(String text:List.of("a".repeat(25),"😀".repeat(25))) {
            assertEquals(text,send(a,chat(text)).getBroadcast().getText());
            assertEquals(text,send(a,pm("Alice",text)).getPrivateMsg().getText());
        }
    }
    @Test void privateMessageIsNotBroadcastOrSavedAndSelfOnlyOnce() throws Exception {
        var a=joined("Alice"); var b=joined("Bob"); var observer=joined("Observer");
        assertTrue(send(a,pm("Bob","secret")).hasPrivateMsg());
        var received=drain(b); assertEquals(1,received.size()); assertEquals("secret",received.get(0).getPrivateMsg().getText());
        assertTrue(drain(observer).isEmpty()); assertTrue(room.getHistory().isEmpty());
        assertTrue(send(a,pm("Alice","self")).hasPrivateMsg());
        assertTrue(drain(b).isEmpty()); assertTrue(drain(observer).isEmpty());
        assertEquals(404,send(a,pm("Missing","hello")).getError().getErrorCode());
        b.close(); drain(a); drain(observer);
        assertEquals(404,send(a,pm("Bob","hello")).getError().getErrorCode());
    }
    @Test void imagesAndAvatarsAcceptValidPngAndJpeg() throws Exception {
        for(String format:List.of("png","jpeg")) {
            byte[] bytes=picture(format); String mime="image/"+format;
            var a=client(); assertTrue(send(a,join(format).toBuilder().setJoin(JoinRequest.newBuilder().setName(format).setAvatar(ByteString.copyFrom(bytes)).setAvatarMime(mime)).build()).hasJoinSuccess());
            for(var ch:channels) drain(ch);
            var request=chat("").toBuilder().setChat(ChatRequest.newBuilder().setImage(ByteString.copyFrom(bytes)).setImageMime(mime)).build();
            assertArrayEquals(bytes,send(a,request).getBroadcast().getImage().toByteArray());
            for(var ch:channels) drain(ch);
            var privateRequest=pm(format,"").toBuilder().setPrivateChat(PrivateChatRequest.newBuilder().setTargetName(format).setImage(ByteString.copyFrom(bytes)).setImageMime(mime)).build();
            assertTrue(send(a,privateRequest).getPrivateMsg().getHasImage());
        }
    }
    @Test void fileLimitsAreEnforcedAtServerForAllPaths() throws Exception {
        byte[] png=picture("png"); var a=joined("Alice");
        for (byte[] bytes:List.of(new byte[0],new byte[]{1,2,3},Arrays.copyOf(png,InputValidation.MAX_IMAGE_BYTES+1))) {
            assertEquals(400,send(a,chat("x").toBuilder().setChat(ChatRequest.newBuilder().setText("x").setImage(ByteString.copyFrom(bytes)).setImageMime("image/png")).build()).getError().getErrorCode());
            assertEquals(400,send(a,pm("Alice","x").toBuilder().setPrivateChat(PrivateChatRequest.newBuilder().setTargetName("Alice").setText("x").setImage(ByteString.copyFrom(bytes)).setImageMime("image/png")).build()).getError().getErrorCode());
        }
        for(int limit:List.of(InputValidation.MAX_IMAGE_BYTES,InputValidation.MAX_AVATAR_BYTES)) {
            assertDoesNotThrow(() -> InputValidation.image(Arrays.copyOf(png,limit),"image/png",limit));
            assertThrows(IllegalArgumentException.class,() -> InputValidation.image(Arrays.copyOf(png,limit+1),"image/png",limit));
        }
        var avatar=join("New").toBuilder().setJoin(JoinRequest.newBuilder().setName("New").setAvatar(ByteString.copyFrom(Arrays.copyOf(png,InputValidation.MAX_AVATAR_BYTES+1))).setAvatarMime("image/png")).build();
        assertEquals(400,send(client(),avatar).getError().getErrorCode()); assertFalse(room.isUserOnline("New"));
    }
    @Test void mismatchedMimeMalformedHeadersAndHugeDimensionsAreRejected() throws Exception {
        byte[] png=picture("png");
        for(String mime:List.of("image/jpeg","image/svg+xml","image/png\" onerror=\"alert(1)"))
            assertThrows(IllegalArgumentException.class,() -> InputValidation.image(png,mime,InputValidation.MAX_IMAGE_BYTES));
        assertThrows(IllegalArgumentException.class,() -> InputValidation.image(Arrays.copyOf(png,8),"image/png",InputValidation.MAX_IMAGE_BYTES));
        // PNG IHDR dimensions, read without allocating a pixel buffer.
        byte[] huge=png.clone(); java.nio.ByteBuffer.wrap(huge,16,8).putInt(4097).putInt(1);
        assertThrows(IllegalArgumentException.class,() -> InputValidation.image(huge,"image/png",InputValidation.MAX_IMAGE_BYTES));
        byte[] tooMany=png.clone(); java.nio.ByteBuffer.wrap(tooMany,16,8).putInt(4096).putInt(4096);
        assertThrows(IllegalArgumentException.class,() -> InputValidation.image(tooMany,"image/png",InputValidation.MAX_IMAGE_BYTES));
    }
    @Test void departedAvatarDoesNotAttachToNextUserWithSameName() throws Exception {
        var a=client(); byte[] png=picture("png");
        send(a,join("Alice").toBuilder().setJoin(JoinRequest.newBuilder().setName("Alice").setAvatar(ByteString.copyFrom(png)).setAvatarMime("image/png")).build());
        send(a,chat("with avatar")); a.close();
        assertNull(room.getUserAvatar("Alice"));
        var next=joined("Alice"); assertFalse(send(next,chat("no avatar")).getBroadcast().hasSenderAvatar());
        assertTrue(room.getHistory().get(0).hasSenderAvatar());
    }
    @Test void corruptProtocolReturnsErrorAndKeepsChannelUsable() throws Exception {
        var a=client(); a.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{(byte)255})));
        assertEquals(400,drain(a).get(0).getError().getErrorCode()); assertTrue(a.isActive());
        assertTrue(send(a,join("Alice")).hasJoinSuccess());
    }
}
