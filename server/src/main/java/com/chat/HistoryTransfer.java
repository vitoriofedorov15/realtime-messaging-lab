package com.chat;

import com.chat.proto.ChatProto.*;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;

/** Byte-limited responses including envelope overhead, all under the room's snapshot lock. */
final class HistoryTransfer {
    static List<ServerResponse> build(ChatRoom room, String requestId, long watermark) {
        List<ServerResponse> result = new ArrayList<>();
        HistoryBatch.Builder batch = HistoryBatch.newBuilder().setSnapshotSequence(watermark);
        List<HistoryMessage> history = room.getHistory();
        for (HistoryMessage message : history) {
            batch.addHistory(message);
            if (wrap(batch,requestId).getSerializedSize() > WebSocketHandler.MAX_RESPONSE_BYTES) {
                batch.removeHistory(batch.getHistoryCount()-1);
                if (batch.getHistoryCount() > 0) result.add(wrap(batch,requestId));
                batch = HistoryBatch.newBuilder().setSnapshotSequence(watermark).addHistory(message);
                if (wrap(batch,requestId).getSerializedSize() > WebSocketHandler.MAX_RESPONSE_BYTES)
                    throw new IllegalArgumentException("Stored history record exceeds protocol limit");
            }
        }
        if (batch.getHistoryCount() > 0) result.add(wrap(batch,requestId));
        HistoryBatch.Builder end = HistoryBatch.newBuilder().setSnapshotSequence(watermark).setComplete(true);
        if (room.getLastImage() != null && history.stream().noneMatch(m -> m.getMessageId().equals(room.getLastImageId()))) {
            end.setLastImage(ByteString.copyFrom(room.getLastImage())).setLastImageMime(room.getLastImageMime())
                    .setLastImageMessageId(room.getLastImageId());
        }
        ServerResponse finalResponse=wrap(end,requestId);
        if (finalResponse.getSerializedSize() > WebSocketHandler.MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("Stored last image exceeds protocol limit");
        result.add(finalResponse);
        return result;
    }
    private static ServerResponse wrap(HistoryBatch.Builder batch,String requestId) {
        return WebSocketHandler.envelope(ServerResponse.ResponseType.HISTORY_BATCH,requestId).setHistoryBatch(batch).build();
    }
}
