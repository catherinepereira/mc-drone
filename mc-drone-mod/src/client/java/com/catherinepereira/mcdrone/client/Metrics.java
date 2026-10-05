package com.catherinepereira.mcdrone.client;

import com.google.gson.JsonObject;

/** Per-second counters, rolled over by ClientRuntime once a second */
public final class Metrics {
	private int simSteps;
	private int captures;
	private double captureMs;
	private double raycastMs;
	private double encodeMs;
	private int droppedFrames;

	private JsonObject last = new JsonObject();

	public synchronized void simStep() {
		this.simSteps++;
	}

	public synchronized void capture(double captureMs, double raycastMs) {
		this.captures++;
		this.captureMs += captureMs;
		this.raycastMs += raycastMs;
	}

	public synchronized void encode(double ms) {
		this.encodeMs += ms;
	}

	public synchronized void dropped() {
		this.droppedFrames++;
	}

	public synchronized JsonObject roll(int fps, int observers, int queueDepth) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "metrics");
		json.addProperty("sps", this.simSteps);
		json.addProperty("fps", fps);
		json.addProperty("captures", this.captures);
		json.addProperty("captureMs", this.captures == 0 ? 0 : this.captureMs / this.captures);
		json.addProperty("raycastMs", this.captures == 0 ? 0 : this.raycastMs / this.captures);
		json.addProperty("encodeMs", this.captures == 0 ? 0 : this.encodeMs / this.captures);
		json.addProperty("droppedFrames", this.droppedFrames);
		json.addProperty("queueDepth", queueDepth);
		json.addProperty("observers", observers);
		this.simSteps = 0;
		this.captures = 0;
		this.captureMs = 0;
		this.raycastMs = 0;
		this.encodeMs = 0;
		this.droppedFrames = 0;
		this.last = json;
		return json;
	}

	public synchronized JsonObject last() {
		return this.last;
	}
}
