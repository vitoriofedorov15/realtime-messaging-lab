package com.chat;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class ChatServer {

    static final int PORT = 8080;

    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup bossGroup   = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChatServerInitializer());

            Channel channel = bootstrap.bind(PORT).sync().channel();

            System.out.println("===========================================");
            System.out.println(" Chat Server started");
            System.out.println(" WebSocket endpoint: ws://localhost:" + PORT + "/ws");
            System.out.println(" Open client/index.html in your browser");
            System.out.println("===========================================");

            channel.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
