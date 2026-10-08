package com.catherinepereira.mcdrone.client.obs;

import com.catherinepereira.mcdrone.client.ClientRuntime;
import com.catherinepereira.mcdrone.client.Config;
import com.catherinepereira.mcdrone.client.DroneLog;
import com.catherinepereira.mcdrone.client.DroneRun;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Turns frames rendered from drone cameras into Observations. A capture snapshots the drone's state when it's asked for,
 * then waits for a frame rendered from that drone's camera, reads the pixels back, and raycasts depth and masks from the
 * same camera. There is one game camera, so drones take turns, in the order they asked, one frame each.
 * With the chase stream on, one more frame renders from the third-person camera
 */
public final class CaptureService {
	private final ClientRuntime runtime;
	private final Minecraft mc;
	private final Map<DroneRun, Pending> pending = new LinkedHashMap<>();
	// set while the next frame renders from the third-person camera for the chase stream
	private @Nullable Consumer<byte[]> chaseListener;
	private int chaseWaitFrames;
	private int waitFrames;
	private long seq;
	// a frame's pixels are on their way back from the GPU
	private boolean reading;
	// frames to wait before a capture, so block edits from a tool sync get re-meshed first
	private int pendingFrames;

	private static final class Pending {
		final JsonObject state;
		final @Nullable JsonObject episode;
		final JsonObject action;
		final long tick;
		int minFrames;
		final List<Consumer<Observation>> listeners = new ArrayList<>();

		Pending(JsonObject state, @Nullable JsonObject episode, JsonObject action, long tick, int minFrames) {
			this.state = state;
			this.episode = episode;
			this.action = action;
			this.tick = tick;
			this.minFrames = minFrames;
		}
	}

	public CaptureService(ClientRuntime runtime, Minecraft mc) {
		this.runtime = runtime;
		this.mc = mc;
	}

	/** Blocks just changed, so the next capture waits for them to re-mesh */
	public void blocksChanged() {
		this.pendingFrames = Math.max(this.pendingFrames, 3);
	}

	/** Snapshots the drone's state now and fills in pixels from a frame rendered from its camera */
	public void request(DroneRun run, Consumer<Observation> listener) {
		Pending p = this.pending.get(run);
		if (p == null) {
			JsonObject episode = run.task().episodeId() == null ? null : run.task().snapshot();
			p = new Pending(this.runtime.stateJson(run), episode, this.runtime.lastAction(run).toJson(), this.runtime.tickCount(), this.pendingFrames);
			this.pendingFrames = 0;
			this.runtime.pinRenderPose(run);
			this.pending.put(run, p);
			if (this.pending.size() == 1) {
				this.aimAtNext();
			}
		}
		p.listeners.add(listener);
	}

	// the next frame renders from the drone first in line, so a handoff between drones costs no extra frame.
	// A chase view on its way keeps the camera on its own drone
	private void aimAtNext() {
		if (this.chaseListener != null || (this.reading && this.runtime.config().wants("chase"))) {
			return;
		}
		Iterator<DroneRun> it = this.pending.keySet().iterator();
		DroneEntity next = it.hasNext() ? it.next().drone() : null;
		if (next != null && this.mc.getCameraEntity() != next) {
			this.mc.setCameraEntity(next);
			this.mc.options.setCameraType(CameraType.FIRST_PERSON);
			this.waitFrames = 0;
		}
	}

	/** Called from the level render END_MAIN event, before the GUI draws */
	public void onFrameRendered(Camera camera) {
		Config config = this.runtime.config();
		DroneLog log = this.runtime.log();
		if (this.chaseListener != null) {
			// the readback callback that asked for the chase view can land after this frame's camera was set up, so wait
			// for a frame rendered from the third-person camera
			if (!camera.isDetached() && this.chaseWaitFrames++ < 5) {
				return;
			}
			Consumer<byte[]> listener = this.chaseListener;
			this.chaseListener = null;
			this.chaseWaitFrames = 0;
			this.mc.options.setCameraType(CameraType.FIRST_PERSON);
			FrameGrabber.grab(this.mc.gameRenderer.mainRenderTarget(), config.chaseWidth, config.chaseHeight, rgb -> this.runtime.onClientThread(() -> listener.accept(rgb)), err -> {
				log.warn("capture.chase_failed", DroneLog.fields("error", err));
				this.runtime.onClientThread(() -> listener.accept(null));
			});
			this.doneIfIdle();
			return;
		}
		Iterator<Map.Entry<DroneRun, Pending>> it = this.pending.entrySet().iterator();
		if (!it.hasNext()) {
			return;
		}
		Map.Entry<DroneRun, Pending> next = it.next();
		DroneRun run = next.getKey();
		Pending p = next.getValue();
		DroneEntity drone = run.drone();
		if (drone == null) {
			log.warn("capture.no_drone", null);
			it.remove();
			return;
		}
		if (camera.entity() != drone) {
			this.aimAtNext();
			return;
		}
		if (p.minFrames > 0) {
			p.minFrames--;
			return;
		}
		if (camera.isDetached() && this.waitFrames++ < 10) {
			return;
		}
		it.remove();
		this.waitFrames = 0;
		this.read(run, p, camera, drone);
		// the readback copy is already queued
		this.aimAtNext();
	}

	// with nothing left to capture, the camera goes back to the drone the player pilots
	private void doneIfIdle() {
		if (this.pending.isEmpty() && this.chaseListener == null && !this.reading) {
			this.runtime.restoreCamera();
		}
	}

	private void read(DroneRun run, Pending p, Camera camera, DroneEntity drone) {
		Config config = this.runtime.config();
		DroneLog log = this.runtime.log();
		int w = config.width;
		int h = config.height;
		RenderTarget target = this.mc.gameRenderer.mainRenderTarget();
		long seq = ++this.seq;
		long t0 = System.nanoTime();
		float[] depth = null;
		short[] mask = null;
		short[] blockStates = null;
		if (config.wants("depth") || config.wants("mask") || config.wants("state")) {
			depth = new float[w * h];
			mask = new short[w * h];
			blockStates = new short[w * h];
			Raycaster.Camera cam = new Raycaster.Camera(
				camera.position(), camera.forwardVector(), camera.upVector(), camera.leftVector(), camera.getFov(), (float) target.width / target.height
			);
			Raycaster.cast(this.mc.level, cam, drone, w, h, config.depthMax, depth, mask, blockStates);
		}
		double raycastMs = (System.nanoTime() - t0) / 1e6;
		float[] outDepth = config.wants("depth") ? depth : null;
		short[] outMask = config.wants("mask") ? mask : null;
		short[] outStates = config.wants("state") ? blockStates : null;

		Consumer<Observation> deliver = obs -> {
			for (Consumer<Observation> listener : p.listeners) {
				listener.accept(obs);
			}
			this.runtime.recorder().onObservation(run.droneId(), obs);
		};
		Consumer<byte[]> finish = rgb -> {
			this.reading = false;
			this.runtime.metrics().capture((System.nanoTime() - t0) / 1e6, raycastMs);
			Observation obs = new Observation(seq, p.tick, w, h, p.state, p.episode, p.action, rgb, outDepth, outMask, outStates);
			if (!config.wants("chase")) {
				deliver.accept(obs);
				this.doneIfIdle();
				return;
			}
			this.mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			this.chaseListener = chase -> {
				obs.chase = chase;
				obs.chaseWidth = config.chaseWidth;
				obs.chaseHeight = config.chaseHeight;
				deliver.accept(obs);
			};
		};
		this.reading = true;
		if (config.wants("rgb")) {
			FrameGrabber.grab(target, w, h, rgb -> this.runtime.onClientThread(() -> finish.accept(rgb)), err -> {
				log.warn("capture.readback_failed", DroneLog.fields("error", err));
				this.runtime.onClientThread(() -> finish.accept(null));
			});
		} else {
			finish.accept(null);
		}
	}
}
