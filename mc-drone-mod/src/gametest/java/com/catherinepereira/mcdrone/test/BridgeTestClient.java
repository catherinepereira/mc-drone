package com.catherinepereira.mcdrone.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal bridge client for the gametest.
 * Never blocks waiting for replies, the test polls between game ticks
 */
final class BridgeTestClient implements WebSocket.Listener {
	record Obs(JsonObject header, byte[] payload) {
	}

	final ConcurrentLinkedQueue<JsonObject> texts = new ConcurrentLinkedQueue<>();
	final ConcurrentLinkedQueue<Obs> frames = new ConcurrentLinkedQueue<>();
	private final ByteArrayOutputStream partialBinary = new ByteArrayOutputStream();
	private final StringBuilder partialText = new StringBuilder();
	private WebSocket socket;

	void connect(int port) throws Exception {
		this.socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), this).get(5, TimeUnit.SECONDS);
	}

	void send(JsonObject message) {
		this.socket.sendText(message.toString(), true).join();
	}

	void close() {
		this.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
	}

	@Override
	public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
		this.partialText.append(data);
		if (last) {
			this.texts.add(JsonParser.parseString(this.partialText.toString()).getAsJsonObject());
			this.partialText.setLength(0);
		}
		ws.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
		byte[] chunk = new byte[data.remaining()];
		data.get(chunk);
		this.partialBinary.writeBytes(chunk);
		if (last) {
			ByteBuffer all = ByteBuffer.wrap(this.partialBinary.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
			this.partialBinary.reset();
			int headerLen = all.getInt();
			byte[] header = new byte[headerLen];
			all.get(header);
			byte[] payload = new byte[all.remaining()];
			all.get(payload);
			this.frames.add(new Obs(JsonParser.parseString(new String(header, StandardCharsets.UTF_8)).getAsJsonObject(), payload));
		}
		ws.request(1);
		return null;
	}
}
