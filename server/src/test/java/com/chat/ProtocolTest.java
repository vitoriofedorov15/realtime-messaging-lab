package com.chat;

import com.chat.proto.ChatProto.*;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @TempDir Path directory;
    ChatRegressionTest fixture;
    @BeforeEach void setup() { fixture=new ChatRegressionTest(); fixture.directory=directory; fixture.setup(); }
    @AfterEach void cleanup() { fixture.cleanup(); }
    List<ServerResponse> request(EmbeddedChannel channel, ClientRequest request) throws Exception {
        channel.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(request.toByteArray())));
        return fixture.drain(channel);
    }
    ClientRequest versioned(ClientRequest request,String id) { return request.toBuilder().setProtocolVersion(2).setRequestId(id).build(); }

    @Test void incompatibleClientGetsExplicitVersionError() throws Exception {
        var response=request(fixture.client(),fixture.join("Old")).get(0);
        assertEquals(2,response.getProtocolVersion());
        assertEquals("UNSUPPORTED_VERSION",response.getError().getCode());
        assertEquals(426,response.getError().getErrorCode());
        assertEquals(0,fixture.room.getActiveUserCount());
    }
    @Test void requestIdAndOneofPayloadAreValidated() throws Exception {
        var ch=fixture.client();
        for(String id:List.of("","x".repeat(65),"bad id")) {
            var r=request(ch,versioned(fixture.join("Alice"),id)).get(0);
            assertEquals("INVALID_REQUEST_ID",r.getError().getCode());
            assertEquals("",r.getRequestId());
        }
        var mismatched=versioned(fixture.join("Alice"),"mismatch").toBuilder()
                .setChat(ChatRequest.newBuilder().setText("hi")).build();
        assertFalse(mismatched.hasJoin()); // oneof clears the previous payload.
        var response=request(ch,mismatched).get(0);
        assertEquals("INVALID_PAYLOAD",response.getError().getCode());
        assertEquals("mismatch",response.getRequestId());
    }
    @Test void repliesCorrelateAndOtherUsersGetUnsolicitedEvents() throws Exception {
        var a=fixture.joined("Alice"); var b=fixture.joined("Bob");
        var own=request(a,versioned(fixture.chat("hello"),"public_1")).get(0);
        var other=fixture.drain(b).get(0);
        assertEquals("public_1",own.getRequestId()); assertEquals("",other.getRequestId());
        assertEquals(own.getBroadcast(),other.getBroadcast());
        assertFalse(own.getBroadcast().getMessageId().isBlank()); assertEquals(1,own.getBroadcast().getSequence());
        var pm=request(a,versioned(fixture.pm("Bob","secret"),"private_1")).get(0);
        assertEquals("Bob",pm.getPrivateMsg().getTargetName());
        assertEquals("private_1",pm.getRequestId());
        assertEquals(pm.getPrivateMsg().getMessageId(),fixture.drain(b).get(0).getPrivateMsg().getMessageId());
        assertEquals(1,fixture.room.getSequence()); // Private messages do not create public sequence gaps.
    }
    @Test void historyBatchesAreByteBoundedAndLiveEventsFollowCompletion() throws Exception {
        byte[] image=new byte[1024*1024], avatar=new byte[200*1024];
        for(int i=0;i<4;i++) fixture.room.addToHistory(HistoryMessage.newBuilder().setSenderName("Past")
                .setText("history "+i).setHasImage(true).setImage(ByteString.copyFrom(image))
                .setImageMime("image/png").setSenderAvatar(ByteString.copyFrom(avatar)).build());
        var reader=fixture.client();
        var responses=request(reader,versioned(fixture.join("Reader"),"join_reader"));
        assertTrue(responses.get(0).hasJoinSuccess());
        assertEquals(List.of("Reader"),responses.get(0).getJoinSuccess().getUsersList());
        assertEquals(4,responses.get(0).getJoinSuccess().getSnapshotSequence());
        List<HistoryMessage> records=new ArrayList<>();
        for(var response:responses) {
            assertTrue(response.getSerializedSize()<=WebSocketHandler.MAX_RESPONSE_BYTES);
            assertEquals("join_reader",response.getRequestId());
            if(response.hasHistoryBatch()) records.addAll(response.getHistoryBatch().getHistoryList());
        }
        assertEquals(4,records.size());
        assertEquals(4,records.stream().map(HistoryMessage::getMessageId).distinct().count());
        assertTrue(responses.get(responses.size()-1).getHistoryBatch().getComplete());
        assertFalse(responses.get(responses.size()-1).getHistoryBatch().hasLastImage());
        var live=request(reader,versioned(fixture.chat("live"),"live_1")).get(0);
        assertEquals(5,live.getBroadcast().getSequence());
    }
    @Test void emptyHistoryHasExplicitCompletionAndMembershipSnapshot() throws Exception {
        fixture.joined("Alice");
        var responses=request(fixture.client(),versioned(fixture.join("Bob"),"join_bob"));
        assertEquals(2,responses.size());
        assertEquals(List.of("Alice","Bob"),responses.get(0).getJoinSuccess().getUsersList());
        assertTrue(responses.get(1).getHistoryBatch().getComplete());
        assertEquals(0,responses.get(1).getHistoryBatch().getHistoryCount());
    }
    @Test void legacyHistoryGetsIdsAndSequencesAndKeepsThemAfterNewSave() throws Exception {
        Path file=directory.resolve("legacy.bin");
        try(var out=java.nio.file.Files.newOutputStream(file)) {
            HistoryMessage.newBuilder().setSenderName("Legacy").setText("old").build().writeDelimitedTo(out);
        }
        ChatRoom migrated=new ChatRoom(file.toFile());
        var old=migrated.getHistory().get(0);
        assertFalse(old.getMessageId().isEmpty()); assertEquals(1,old.getSequence());
        migrated.addToHistory(HistoryMessage.newBuilder().setText("next").build());
        var reopened=new ChatRoom(file.toFile());
        assertEquals(old.getMessageId(),reopened.getHistory().get(0).getMessageId());
        assertEquals(2,reopened.getSequence());
    }
    @Test void outsideWindowLastImageIsSentOnlyInFinalBatch() {
        fixture.room.addToHistory(HistoryMessage.newBuilder().setHasImage(true).setImage(ByteString.copyFrom(new byte[]{1})).setImageMime("image/png").build());
        for(int i=0;i<50;i++) fixture.room.addToHistory(HistoryMessage.newBuilder().setText("text").build());
        var responses=HistoryTransfer.build(fixture.room,"join",fixture.room.getSequence());
        var end=responses.get(responses.size()-1).getHistoryBatch();
        assertTrue(end.getComplete()); assertTrue(end.hasLastImage());
        assertEquals(fixture.room.getLastImageId(),end.getLastImageMessageId());
    }
    @Test void fragmentedBinaryRequestIsAggregated() throws Exception {
        var ch=new EmbeddedChannel(new WebSocketFrameAggregator(2*1024*1024),new WebSocketHandler(fixture.room));
        fixture.channels.add(ch);
        byte[] bytes=versioned(fixture.join("Fragmented"),"fragment").toByteArray();
        ch.writeInbound(new BinaryWebSocketFrame(false,0,Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes,0,5))));
        assertTrue(fixture.drain(ch).isEmpty());
        ch.writeInbound(new ContinuationWebSocketFrame(true,0,Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes,5,bytes.length))));
        var responses=fixture.drain(ch);
        assertTrue(responses.get(0).hasJoinSuccess()); assertTrue(responses.get(1).getHistoryBatch().getComplete());
    }
}
