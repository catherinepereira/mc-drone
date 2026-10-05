package com.catherinepereira.mcdrone.client.bridge;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.DroneLog;
import com.catherinepereira.mcdrone.client.obs.Observation;
import com.catherinepereira.mcdrone.client.obs.Png;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** REST endpoints under /api, see protocol/PROTOCOL.md */
final class HttpApi extends SimpleChannelInboundHandler<FullHttpRequest> {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

	private final ClientRuntime runtime;

	HttpApi(ClientRuntime runtime) {
		this.runtime = runtime;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
		QueryStringDecoder query = new QueryStringDecoder(request.uri());
		String[] parts = query.path().replaceAll("^/+|/+$", "").split("/");
		HttpMethod method = request.method();
		boolean keepAlive = HttpUtil.isKeepAlive(request);
		try {
			if (parts.length < 2 || !parts[0].equals("api")) {
				this.send(ctx, keepAlive, error(HttpResponseStatus.NOT_FOUND, "not found"));
				return;
			}
			switch (parts[1]) {
				case "status" -> this.send(ctx, keepAlive, json(this.runtime.callOnClient(this.runtime::statusJson)));
				case "config" -> {
					if (method.equals(HttpMethod.PUT)) {
						JsonObject patch = JsonParser.parseString(request.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
						this.send(ctx, keepAlive, json(this.runtime.callOnClient(() -> this.runtime.updateConfig(patch))));
					} else {
						this.send(ctx, keepAlive, json(this.runtime.callOnClient(() -> this.runtime.config().toJson())));
					}
				}
				case "logs" -> {
					int limit = Integer.parseInt(param(query, "limit", "500"));
					JsonArray lines = new JsonArray();
					this.runtime.log().tail(Math.clamp(limit, 1, 2000)).forEach(lines::add);
					this.send(ctx, keepAlive, json(lines));
				}
				case "episodes" -> this.send(ctx, keepAlive, this.episodes(parts, method));
				case "states" -> this.send(ctx, keepAlive, json(stateNames()));
				default -> this.send(ctx, keepAlive, error(HttpResponseStatus.NOT_FOUND, "not found"));
			}
		} catch (RuntimeException | IOException e) {
			this.runtime.log().warn("http.error", DroneLog.fields("path", query.path(), "error", e.toString()));
			this.send(ctx, keepAlive, error(HttpResponseStatus.BAD_REQUEST, e.getMessage() == null ? e.toString() : e.getMessage()));
		}
	}

	/** Names for the state stream: entry i is block state id i, as "minecraft:wheat[age=7]", the stream holds 1 + id */
	private static JsonArray stateNames() {
		if (STATE_NAMES == null) {
			JsonArray names = new JsonArray();
			for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
				while (names.size() < Block.getId(state)) {
					names.add("");
				}
				names.add(BlockStateParser.serialize(state));
			}
			STATE_NAMES = names;
		}
		return STATE_NAMES;
	}

	private static JsonArray STATE_NAMES;

	private FullHttpResponse episodes(String[] parts, HttpMethod method) throws IOException {
		Path data = this.runtime.recorder().dataDir();
		if (parts.length == 2) {
			return json(listEpisodes(data));
		}
		if (parts.length < 4 || !NAME.matcher(parts[2]).matches() || !NAME.matcher(parts[3]).matches() || parts[3].equals("..")) {
			return error(HttpResponseStatus.BAD_REQUEST, "bad episode path");
		}
		Path dir = data.resolve(parts[2]).resolve(parts[3]);
		if (!Files.isRegularFile(dir.resolve("meta.json"))) {
			return error(HttpResponseStatus.NOT_FOUND, "no such episode");
		}
		if (parts.length == 4 && method.equals(HttpMethod.DELETE)) {
			// moved aside rather than deleted, so a mistaken click is recoverable
			Path trash = data.resolve(".trash").resolve(parts[2]);
			Files.createDirectories(trash);
			Files.move(dir, trash.resolve(parts[3]), StandardCopyOption.REPLACE_EXISTING);
			this.runtime.log().info("episode.trashed", DroneLog.fields("task", parts[2], "id", parts[3]));
			JsonObject ok = new JsonObject();
			ok.addProperty("ok", true);
			return json(ok);
		}
		JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("meta.json"))).getAsJsonObject();
		List<String> steps = Files.exists(dir.resolve("steps.jsonl")) ? Files.readAllLines(dir.resolve("steps.jsonl")) : List.of();
		if (parts.length == 4) {
			JsonObject body = new JsonObject();
			body.add("meta", meta);
			JsonArray rows = new JsonArray();
			steps.stream().filter(s -> !s.isBlank()).map(JsonParser::parseString).forEach(rows::add);
			body.add("steps", rows);
			return json(body);
		}
		if (parts.length == 6 && parts[4].equals("frame")) {
			int n = Integer.parseInt(parts[5]);
			if (n < 0 || n >= steps.size()) {
				return error(HttpResponseStatus.NOT_FOUND, "no such frame");
			}
			return binary(frame(dir, meta, JsonParser.parseString(steps.get(n)).getAsJsonObject(), n));
		}
		return error(HttpResponseStatus.NOT_FOUND, "not found");
	}

	private static byte[] frame(Path dir, JsonObject meta, JsonObject row, int n) throws IOException {
		int width = meta.get("width").getAsInt();
		int height = meta.get("height").getAsInt();
		String name = String.format("%06d", n);
		Path rgbPath = dir.resolve("rgb").resolve(name + ".png");
		Path depthPath = dir.resolve("depth").resolve(name + ".f32");
		Path maskPath = dir.resolve("mask").resolve(name + ".png");
		Path statePath = dir.resolve("state").resolve(name + ".png");
		byte[] rgb = Files.exists(rgbPath) ? Png.readRgb(rgbPath, width, height) : null;
		float[] depth = null;
		if (Files.exists(depthPath)) {
			ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(depthPath)).order(ByteOrder.LITTLE_ENDIAN);
			depth = new float[width * height];
			buf.asFloatBuffer().get(depth);
		}
		short[] mask = Files.exists(maskPath) ? Png.readGray16(maskPath, width, height) : null;
		short[] blockStates = Files.exists(statePath) ? Png.readGray16(statePath, width, height) : null;
		JsonObject episode = new JsonObject();
		episode.addProperty("id", meta.get("id").getAsString());
		episode.addProperty("task", meta.get("task").getAsString());
		episode.addProperty("step", n);
		episode.add("reward", row.get("reward"));
		episode.add("done", row.get("done"));
		JsonElement action = row.get("action");
		return new Observation(
			n, row.get("tick").getAsLong(), width, height, row.getAsJsonObject("state"), episode,
			action.isJsonObject() ? action.getAsJsonObject() : null, rgb, depth, mask, blockStates
		).encode(null);
	}

	private static JsonArray listEpisodes(Path data) throws IOException {
		List<JsonObject> metas = new ArrayList<>();
		if (Files.isDirectory(data)) {
			try (Stream<Path> tasks = Files.list(data)) {
				for (Path task : tasks.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).toList()) {
					try (Stream<Path> eps = Files.list(task)) {
						for (Path ep : eps.filter(p -> Files.isRegularFile(p.resolve("meta.json"))).toList()) {
							try {
								metas.add(JsonParser.parseString(Files.readString(ep.resolve("meta.json"))).getAsJsonObject());
							} catch (RuntimeException e) {
								// a half-written meta.json from an episode still being recorded
							}
						}
					}
				}
			}
		}
		metas.sort(Comparator.comparing((JsonObject m) -> m.has("startedAt") ? m.get("startedAt").getAsString() : "").reversed());
		JsonArray out = new JsonArray();
		metas.forEach(out::add);
		return out;
	}

	private static String param(QueryStringDecoder query, String name, String fallback) {
		List<String> values = query.parameters().get(name);
		return values == null || values.isEmpty() ? fallback : values.getFirst();
	}

	private static FullHttpResponse json(JsonElement body) {
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(body.toString(), StandardCharsets.UTF_8)
		);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		return response;
	}

	private static FullHttpResponse binary(byte[] body) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
		return response;
	}

	private static FullHttpResponse error(HttpResponseStatus status, String message) {
		JsonObject body = new JsonObject();
		body.addProperty("error", message);
		FullHttpResponse response = json(body);
		response.setStatus(status);
		return response;
	}

	private void send(ChannelHandlerContext ctx, boolean keepAlive, FullHttpResponse response) {
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
		HttpUtil.setKeepAlive(response, keepAlive);
		var future = ctx.writeAndFlush(response);
		if (!keepAlive) {
			future.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
		}
	}
}
