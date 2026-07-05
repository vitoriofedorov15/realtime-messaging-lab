package com.chat;

import com.chat.proto.ChatProto;
import com.google.protobuf.ByteString;







import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

public class WebSocketHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private final ChatRoom chatRoom;

    public WebSocketHandler(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    // ------------------------------------------------------------------ inbound

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        byte[] bytes = new byte[frame.content().readableBytes()];
        frame.content().readBytes(bytes);

        ChatProto.ClientRequest request = ChatProto.ClientRequest.parseFrom(bytes);

        switch (request.getType()) {
            case JOIN         -> handleJoin(ctx, request);
            case CHAT         -> handleChat(ctx, request);
            case PRIVATE_CHAT -> handlePrivateChat(ctx, request);
            default           -> sendError(ctx.channel(), 400, "Unknown request type");
        }
    }

    // ------------------------------------------------------------------ JOIN

    private void handleJoin(ChannelHandlerContext ctx, ChatProto.ClientRequest request) {

        if (!request.hasJoin()) {
            sendError(ctx.channel(), 400, "JOIN request payload is missing");
            return;
        }

        ChatProto.JoinRequest joinReq = request.getJoin();
        String name = joinReq.getName().trim();

        if (name.isEmpty() || name.length() > 50) {
            sendError(ctx.channel(), 400, "Name must be 1–50 characters");
            return;
        }


        byte[] avatar    = null;
        String avatarMime = "";
        if (joinReq.hasAvatar()) {
            avatar     = joinReq.getAvatar().toByteArray();
            avatarMime = joinReq.getAvatarMime();
        }

        ChatRoom.JoinResult result = chatRoom.join(ctx.channel(), name, avatar, avatarMime);

        if (result == ChatRoom.JoinResult.NAME_TAKEN) {
            sendJoinError(ctx.channel(), 409, "Name '" + name + "' is already taken");
            return;
        }
        if (result == ChatRoom.JoinResult.ROOM_FULL) {
            sendJoinError(ctx.channel(), 503, "Chat room is full (max 100 users)");
            return;
        }


        ChatProto.JoinSuccessResponse.Builder joinSuccess = ChatProto.JoinSuccessResponse.newBuilder()
                .addAllHistory(chatRoom.getHistory())
                .setActiveUsers(chatRoom.getActiveUserCount());

        byte[] lastImg = chatRoom.getLastImage();
        if (lastImg != null) {
            joinSuccess.setLastImage(ByteString.copyFrom(lastImg));
            joinSuccess.setLastImageMime(chatRoom.getLastImageMime());
        }

        sendResponse(ctx.channel(), ChatProto.ServerResponse.newBuilder()
                .setType(ChatProto.ServerResponse.ResponseType.JOIN_SUCCESS)
                .setJoinSuccess(joinSuccess.build())
                .build());

        System.out.printf("[JOIN] %s joined. Active users: %d%n", name, chatRoom.getActiveUserCount());


        ChatProto.ServerResponse userJoined = ChatProto.ServerResponse.newBuilder()
                .setType(ChatProto.ServerResponse.ResponseType.USER_JOINED)
                .setUserEvent(ChatProto.UserEvent.newBuilder()
                        .setUserName(name)
                        .setJoined(true)
                        .setActiveUsers(chatRoom.getActiveUserCount())
                        .build())
                .build();
        broadcastToOthers(ctx.channel(), userJoined);
    }

    // ------------------------------------------------------------------ CHAT

    private void handleChat(ChannelHandlerContext ctx, ChatProto.ClientRequest request) {
        String senderName = chatRoom.getUserName(ctx.channel());
        if (senderName == null) {
            sendError(ctx.channel(), 401, "You must join the chat first");
            return;
        }


        if (!request.hasChat()) {
            sendError(ctx.channel(), 400, "CHAT request payload is missing");
            return;
        }

        ChatProto.ChatRequest chatReq = request.getChat();
        String text = chatReq.getText();
        if (text.length() > 25) {
            text = text.substring(0, 25);
        }

        long timestamp = System.currentTimeMillis();

        ChatProto.BroadcastMessage.Builder broadcast = ChatProto.BroadcastMessage.newBuilder()
                .setSenderName(senderName)
                .setText(text)
                .setTimestamp(timestamp)
                .setHasImage(false);


        byte[] avatar = chatRoom.getUserAvatar(senderName);
        if (avatar != null) {
            broadcast.setSenderAvatar(ByteString.copyFrom(avatar));
            broadcast.setSenderAvatarMime(chatRoom.getUserAvatarMime(senderName));
        }


        ChatProto.HistoryMessage.Builder histMsg = ChatProto.HistoryMessage.newBuilder()
                .setSenderName(senderName)
                .setText(text)
                .setTimestamp(timestamp)
                .setHasImage(false)
                .setIsPrivate(false);


        if (avatar != null) {
            histMsg.setSenderAvatar(ByteString.copyFrom(avatar));
            histMsg.setSenderAvatarMime(chatRoom.getUserAvatarMime(senderName));
        }

        if (chatReq.hasImage()) {
            byte[] imgBytes = chatReq.getImage().toByteArray();
            if (imgBytes.length > 0) {
                broadcast.setHasImage(true)
                         .setImage(ByteString.copyFrom(imgBytes))
                         .setImageMime(chatReq.getImageMime());
                histMsg.setHasImage(true)
                       .setImage(chatReq.getImage())
                       .setImageMime(chatReq.getImageMime());
            }
        }

        chatRoom.addToHistory(histMsg.build());

        ChatProto.ServerResponse response = ChatProto.ServerResponse.newBuilder()
                .setType(ChatProto.ServerResponse.ResponseType.NEW_MESSAGE)
                .setBroadcast(broadcast.build())
                .build();

        broadcastToAll(response);
        System.out.printf("[CHAT] %s: %s%n", senderName, text);
    }

    // ------------------------------------------------------------------ PRIVATE CHAT

    private void handlePrivateChat(ChannelHandlerContext ctx, ChatProto.ClientRequest request) {
        String senderName = chatRoom.getUserName(ctx.channel());
        if (senderName == null) {
            sendError(ctx.channel(), 401, "You must join the chat first");
            return;
        }


        if (!request.hasPrivateChat()) {
            sendError(ctx.channel(), 400, "PRIVATE_CHAT request payload is missing");
            return;
        }

        ChatProto.PrivateChatRequest privateReq = request.getPrivateChat();
        String targetName = privateReq.getTargetName().trim();

        Channel targetChannel = chatRoom.getChannelByName(targetName);
        if (targetChannel == null) {
            sendError(ctx.channel(), 404, "User '" + targetName + "' is not in the chat");
            return;
        }

        long timestamp = System.currentTimeMillis();

        ChatProto.PrivateMessage.Builder privateMsg = ChatProto.PrivateMessage.newBuilder()
                .setSenderName(senderName)
                .setText(privateReq.getText())
                .setTimestamp(timestamp)
                .setHasImage(false);

        if (privateReq.hasImage()) {
            byte[] imgBytes = privateReq.getImage().toByteArray();
            if (imgBytes.length > 0) {
                privateMsg.setHasImage(true)
                          .setImage(privateReq.getImage())
                          .setImageMime(privateReq.getImageMime());
            }
        }

        ChatProto.ServerResponse serverResponse = ChatProto.ServerResponse.newBuilder()
                .setType(ChatProto.ServerResponse.ResponseType.PRIVATE_MESSAGE)
                .setPrivateMsg(privateMsg.build())
                .build();


        sendResponse(targetChannel, serverResponse);
        sendResponse(ctx.channel(), serverResponse);

        // Private messages are NOT stored in shared history — visible only to the two participants
        System.out.printf("[PM] %s -> %s: %s%n", senderName, targetName, privateReq.getText());
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String name = chatRoom.getUserName(ctx.channel());
        chatRoom.leave(ctx.channel());

        if (name != null) {
            System.out.printf("[LEAVE] %s disconnected. Active users: %d%n",
                    name, chatRoom.getActiveUserCount());

            ChatProto.ServerResponse userLeft = ChatProto.ServerResponse.newBuilder()
                    .setType(ChatProto.ServerResponse.ResponseType.USER_LEFT)
                    .setUserEvent(ChatProto.UserEvent.newBuilder()
                            .setUserName(name)
                            .setJoined(false)
                            .setActiveUsers(chatRoom.getActiveUserCount())
                            .build())
                    .build();

            broadcastToAll(userLeft);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[ERROR] " + cause.getMessage());
        ctx.close();
    }

    // ------------------------------------------------------------------ helpers

    private void sendResponse(Channel channel, ChatProto.ServerResponse response) {
        if (channel.isActive()) {
            channel.writeAndFlush(new BinaryWebSocketFrame(
                    Unpooled.wrappedBuffer(response.toByteArray())));
        }
    }

    private void sendError(Channel channel, int code, String message) {
        sendResponse(channel, ChatProto.ServerResponse.newBuilder()
                .setType(ChatProto.ServerResponse.ResponseType.ERROR)
                .setError(ChatProto.ErrorResponse.newBuilder()
                        .setErrorCode(code)
                        .setErrorMessage(message)
                        .build())
                .build());
    }

    private void sendJoinError(Channel channel, int code, String message) {
        sendResponse(channel, ChatProto.ServerResponse.newBuilder()
                .setType(ChatProto.ServerResponse.ResponseType.JOIN_ERROR)
                .setError(ChatProto.ErrorResponse.newBuilder()
                        .setErrorCode(code)
                        .setErrorMessage(message)
                        .build())
                .build());
    }

    private void broadcastToAll(ChatProto.ServerResponse response) {
        for (Channel ch : chatRoom.getAllChannels()) {
            sendResponse(ch, response);
        }
    }

    private void broadcastToOthers(Channel sender, ChatProto.ServerResponse response) {
        for (Channel ch : chatRoom.getAllChannels()) {
            if (ch != sender) {
                sendResponse(ch, response);
            }
        }
    }
}
