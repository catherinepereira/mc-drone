package com.catherinepereira.mcdrone.client.bridge;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.concurrent.atomic.AtomicInteger;

/** One WebSocket connection */
public final class Session {
	private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

	public final int id = NEXT_ID.getAndIncrement();
	public final Channel channel;
	public volatile String client = "unknown";
	public volatile boolean greeted;
	public volatile boolean wantsObs = true;
	public volatile boolean wantsLogs;
	public volatile boolean wantsMetrics;

	Session(Channel channel) {
		this.channel = channel;
	}

	public void send(JsonObject message) {
		if (this.channel.isActive()) {
			this.channel.writeAndFlush(new TextWebSocketFrame(message.toString()));
		}
	}

	/** Returns false when the socket is backed up and the frame was dropped */
	public boolean sendBinary(byte[] data, boolean required) {
		if (!this.channel.isActive()) {
			return false;
		}
		if (!required && !this.channel.isWritable()) {
			return false;
		}
		this.channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
		return true;
	}

	public void error(String message, Integer replyTo) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "error");
		json.addProperty("message", message);
		if (replyTo != null) {
			json.addProperty("replyTo", replyTo);
		}
		this.send(json);
	}
}
