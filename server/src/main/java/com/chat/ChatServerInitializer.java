package com.chat;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.stream.ChunkedWriteHandler;


public class ChatServerInitializer extends ChannelInitializer<SocketChannel> {

    // One shared ChatRoom for the whole server
    private final ChatRoom chatRoom;

    public ChatServerInitializer(ChatRoom chatRoom) { this.chatRoom = chatRoom; }

    // Max frame / aggregated HTTP body size: 2 MB (enough for images ≤ 1 MB + overhead)
    private static final int MAX_CONTENT_LENGTH = 2 * 1024 * 1024;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
        pipeline.addLast(new ChunkedWriteHandler());
        pipeline.addLast(new WebSocketServerCompressionHandler());
        pipeline.addLast(new WebSocketServerProtocolHandler(
                "/ws",
                null,
                true,
                MAX_CONTENT_LENGTH
        ));
        pipeline.addLast(new WebSocketFrameAggregator(MAX_CONTENT_LENGTH));
        pipeline.addLast(new WebSocketHandler(chatRoom));
    }
}
