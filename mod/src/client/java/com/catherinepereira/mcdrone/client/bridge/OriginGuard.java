package com.catherinepereira.mcdrone.client.bridge;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;

/** Rejects requests from browser pages other than the dashboard */
final class OriginGuard extends ChannelInboundHandlerAdapter {
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof FullHttpRequest request) {
			String origin = request.headers().get(HttpHeaderNames.ORIGIN);
			if (origin != null && !BridgeServer.ALLOWED_ORIGINS.contains(origin)) {
				request.release();
				DefaultFullHttpResponse response = new DefaultFullHttpResponse(
					HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.copiedBuffer("origin not allowed", StandardCharsets.UTF_8)
				);
				response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				return;
			}
		}
		ctx.fireChannelRead(msg);
	}
}
