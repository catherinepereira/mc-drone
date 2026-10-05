package com.catherinepereira.mcdrone.client.bridge;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.DroneLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback HTTP and WebSocket server.
 * Netty threads only parse and route, everything that touches the game hops to the client thread
 */
public final class BridgeServer {
	static final AttributeKey<Session> SESSION = AttributeKey.valueOf("mcdrone.session");
	// browsers send Origin, scripts don't, so this blocks other web pages from driving the game
	static final List<String> ALLOWED_ORIGINS = List.of("http://localhost:5318", "http://127.0.0.1:5318");
	private static final int MAX_MESSAGE = 1 << 20;

	private final ClientRuntime runtime;
	private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
	private EventLoopGroup group;
	private Channel serverChannel;

	public BridgeServer(ClientRuntime runtime) {
		this.runtime = runtime;
	}

	public Set<Session> sessions() {
		return this.sessions;
	}

	public void start(int port) {
		this.group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
		ServerBootstrap bootstrap = new ServerBootstrap()
			.group(this.group)
			.channel(NioServerSocketChannel.class)
			.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1 << 20, 4 << 20))
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) {
					ch.pipeline()
						.addLast(new HttpServerCodec())
						.addLast(new HttpObjectAggregator(MAX_MESSAGE))
						.addLast(new OriginGuard())
						.addLast(new WebSocketServerProtocolHandler(
							WebSocketServerProtocolConfig.newBuilder().websocketPath("/ws").maxFramePayloadLength(MAX_MESSAGE).build()
						))
						.addLast(new HttpApi(BridgeServer.this.runtime))
						.addLast(new FrameHandler());
				}
			});
		try {
			this.serverChannel = bootstrap.bind(InetAddress.getLoopbackAddress(), port).sync().channel();
			this.runtime.log().info("bridge.listening", DroneLog.fields("port", port));
		} catch (Exception e) {
			this.runtime.log().error("bridge.bind_failed", e, DroneLog.fields("port", port));
		}
	}

	public void stop() {
		if (this.serverChannel != null) {
			this.serverChannel.close();
		}
		if (this.group != null) {
			this.group.shutdownGracefully();
		}
	}

	private final class FrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
				Session session = new Session(ctx.channel());
				ctx.channel().attr(SESSION).set(session);
				BridgeServer.this.sessions.add(session);
				BridgeServer.this.runtime.log().info("bridge.connect", DroneLog.fields("session", session.id, "remote", String.valueOf(ctx.channel().remoteAddress())));
			}
			super.userEventTriggered(ctx, evt);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
			Session session = ctx.channel().attr(SESSION).get();
			if (session == null) {
				return;
			}
			if (!(frame instanceof TextWebSocketFrame text)) {
				session.error("binary messages are not accepted", null);
				return;
			}
			JsonObject message;
			try {
				message = JsonParser.parseString(text.text()).getAsJsonObject();
			} catch (RuntimeException e) {
				session.error("invalid JSON: " + e.getMessage(), null);
				return;
			}
			BridgeServer.this.runtime.onClientThread(() -> BridgeServer.this.runtime.handleMessage(session, message));
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			Session session = ctx.channel().attr(SESSION).get();
			if (session != null) {
				BridgeServer.this.sessions.remove(session);
				BridgeServer.this.runtime.onClientThread(() -> BridgeServer.this.runtime.onDisconnect(session));
			}
			super.channelInactive(ctx);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			BridgeServer.this.runtime.log().warn("bridge.channel_error", DroneLog.fields("error", cause.toString()));
			ctx.close();
		}
	}
}
