package com.chat;

import com.chat.proto.ChatProto.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.List;
import java.util.UUID;

/** Room monitor defines ordering; every outgoing write is queued in that order. */
public class WebSocketHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
    static final int VERSION = 2;
    static final int MAX_RESPONSE_BYTES = 1536 * 1024;
    private final ChatRoom room;

    public WebSocketHandler(ChatRoom room) { this.room = room; }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        byte[] bytes = new byte[frame.content().readableBytes()];
        frame.content().readBytes(bytes);
        synchronized (room) {
            ClientRequest request;
            try { request = ClientRequest.parseFrom(bytes); }
            catch (InvalidProtocolBufferException e) {
                error(ctx.channel(), "", 400, "INVALID_PROTOBUF", "Invalid Protobuf message", false);
                return;
            }
            String id = request.getRequestId();
            // Never echo an unbounded untrusted ID into a response.
            if (id.length() > 64) id = "";
            if (request.getProtocolVersion() != VERSION) {
                error(ctx.channel(), id, 426, "UNSUPPORTED_VERSION", "Protocol version 2 required", false);
                return;
            }
            if (!request.getRequestId().matches("[A-Za-z0-9_-]{1,64}")) {
                error(ctx.channel(), "", 400, "INVALID_REQUEST_ID", "request_id must contain 1–64 ASCII letters, digits, _ or -", false);
                return;
            }
            boolean matches = switch (request.getType()) {
                case JOIN -> request.hasJoin();
                case CHAT -> request.hasChat();
                case PRIVATE_CHAT -> request.hasPrivateChat();
                default -> false;
            };
            if (!matches) {
                error(ctx.channel(), id, 400, "INVALID_PAYLOAD", "Request type and payload must match", false);
                return;
            }
            try {
                switch (request.getType()) {
                    case JOIN -> join(ctx.channel(), request);
                    case CHAT -> chat(ctx.channel(), request);
                    case PRIVATE_CHAT -> privateChat(ctx.channel(), request);
                    default -> throw new IllegalArgumentException("Unknown request type");
                }
            } catch (IllegalArgumentException e) {
                error(ctx.channel(), id, 400, "INVALID_INPUT", e.getMessage(), request.hasJoin() && room.getUserName(ctx.channel()) == null);
            }
        }
    }

    private void join(Channel channel, ClientRequest request) {
        String id = request.getRequestId();
        if (room.getUserName(channel) != null) {
            error(channel,id,409,"ALREADY_JOINED","This connection has already joined",false); return;
        }
        JoinRequest payload = request.getJoin();
        String name = payload.getName().trim();
        InputValidation.name(name);
        byte[] avatar = payload.hasAvatar() ? payload.getAvatar().toByteArray() : null;
        if (avatar != null) InputValidation.image(avatar,payload.getAvatarMime(),InputValidation.MAX_AVATAR_BYTES);
        // Build the bounded snapshot before mutating membership; invalid legacy records cannot half-join.
        long watermark = room.getSequence();
        List<ServerResponse> batches = HistoryTransfer.build(room, id, watermark);
        ChatRoom.JoinResult result = room.join(channel,name,avatar,payload.getAvatarMime());
        if (result != ChatRoom.JoinResult.OK) {
            error(channel,id,result == ChatRoom.JoinResult.ROOM_FULL ? 503 : 409,result.name(),
                    result == ChatRoom.JoinResult.ROOM_FULL ? "Room is full" : "Name is already taken",true); return;
        }
        send(channel,envelope(ServerResponse.ResponseType.JOIN_SUCCESS,id)
                .setJoinSuccess(JoinSuccessResponse.newBuilder().setActiveUsers(room.getActiveUserCount())
                        .addAllUsers(room.getUsers()).setSnapshotSequence(watermark)).build());
        for (ServerResponse batch : batches) send(channel,batch);
        broadcast(userEvent(name,true),channel);
    }

    private boolean requireJoined(Channel channel, String id) {
        if (room.getUserName(channel) != null) return true;
        error(channel,id,401,"NOT_JOINED","Join the chat first",false); return false;
    }

    private void chat(Channel channel, ClientRequest request) {
        String id=request.getRequestId();
        if (!requireJoined(channel,id)) return;
        ChatRequest payload=request.getChat();
        InputValidation.message(payload.getText(),payload.hasImage() && !payload.getImage().isEmpty());
        if (payload.hasImage()) InputValidation.image(payload.getImage().toByteArray(),payload.getImageMime(),InputValidation.MAX_IMAGE_BYTES);
        String name=room.getUserName(channel);
        HistoryMessage.Builder record=HistoryMessage.newBuilder().setSenderName(name).setText(payload.getText())
                .setTimestamp(System.currentTimeMillis()).setHasImage(payload.hasImage());
        if (payload.hasImage()) record.setImage(payload.getImage()).setImageMime(payload.getImageMime());
        byte[] avatar=room.getUserAvatar(name);
        if (avatar != null) record.setSenderAvatar(ByteString.copyFrom(avatar)).setSenderAvatarMime(room.getUserAvatarMime(name));
        HistoryMessage stored=room.addToHistory(record.build());
        BroadcastMessage.Builder event=BroadcastMessage.newBuilder().setSenderName(stored.getSenderName())
                .setText(stored.getText()).setTimestamp(stored.getTimestamp()).setHasImage(stored.getHasImage())
                .setMessageId(stored.getMessageId()).setSequence(stored.getSequence());
        if (stored.hasImage()) event.setImage(stored.getImage()).setImageMime(stored.getImageMime());
        if (stored.hasSenderAvatar()) event.setSenderAvatar(stored.getSenderAvatar()).setSenderAvatarMime(stored.getSenderAvatarMime());
        // Sender gets the correlated result; others get the same message as an unsolicited event.
        send(channel,envelope(ServerResponse.ResponseType.NEW_MESSAGE,id).setBroadcast(event).build());
        broadcast(envelope(ServerResponse.ResponseType.NEW_MESSAGE,"").setBroadcast(event).build(),channel);
    }

    private void privateChat(Channel channel, ClientRequest request) {
        String id=request.getRequestId();
        if (!requireJoined(channel,id)) return;
        PrivateChatRequest payload=request.getPrivateChat();
        String target=payload.getTargetName().trim();
        InputValidation.name(target);
        InputValidation.message(payload.getText(),payload.hasImage() && !payload.getImage().isEmpty());
        if (payload.hasImage()) InputValidation.image(payload.getImage().toByteArray(),payload.getImageMime(),InputValidation.MAX_IMAGE_BYTES);
        Channel recipient=room.getChannelByName(target);
        if (recipient == null || !recipient.isActive()) {
            error(channel,id,404,"RECIPIENT_OFFLINE","Recipient is not in the chat",false); return;
        }
        PrivateMessage.Builder event=PrivateMessage.newBuilder().setSenderName(room.getUserName(channel))
                .setTargetName(target).setMessageId(UUID.randomUUID().toString())
                .setText(payload.getText()).setTimestamp(System.currentTimeMillis()).setHasImage(payload.hasImage());
        if (payload.hasImage()) event.setImage(payload.getImage()).setImageMime(payload.getImageMime());
        send(channel,envelope(ServerResponse.ResponseType.PRIVATE_MESSAGE,id).setPrivateMsg(event).build());
        if (recipient != channel) send(recipient,envelope(ServerResponse.ResponseType.PRIVATE_MESSAGE,"").setPrivateMsg(event).build());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        synchronized (room) {
            String name=room.getUserName(ctx.channel());
            room.leave(ctx.channel());
            if (name != null) broadcast(userEvent(name,false),null);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[ERROR] " + cause.getMessage()); ctx.close();
    }

    private ServerResponse userEvent(String name, boolean joined) {
        return envelope(joined ? ServerResponse.ResponseType.USER_JOINED : ServerResponse.ResponseType.USER_LEFT,"")
                .setUserEvent(UserEvent.newBuilder().setUserName(name).setJoined(joined).setActiveUsers(room.getActiveUserCount())).build();
    }

    static ServerResponse.Builder envelope(ServerResponse.ResponseType type, String requestId) {
        return ServerResponse.newBuilder().setType(type).setProtocolVersion(VERSION).setRequestId(requestId);
    }

    private void error(Channel channel,String id,int status,String code,String message,boolean join) {
        send(channel,envelope(join ? ServerResponse.ResponseType.JOIN_ERROR : ServerResponse.ResponseType.ERROR,id)
                .setError(ErrorResponse.newBuilder().setErrorCode(status).setCode(code).setErrorMessage(message)).build());
    }

    private void send(Channel channel, ServerResponse response) {
        if (response.getSerializedSize() > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Response exceeds protocol limit");
        // Always queue, even on this channel's event loop: direct writes could overtake previously queued writes.
        channel.eventLoop().execute(() -> {
            if (channel.isActive()) channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(response.toByteArray())));
        });
    }

    private void broadcast(ServerResponse response, Channel except) {
        for (Channel channel : room.getAllChannels()) if (channel != except) send(channel,response);
    }
}
