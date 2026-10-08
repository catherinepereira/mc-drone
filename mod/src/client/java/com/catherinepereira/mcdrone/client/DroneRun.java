package com.catherinepereira.mcdrone.client;

import com.catherinepereira.mcdrone.client.bridge.Session;
import com.catherinepereira.mcdrone.client.task.TaskScorer;
import com.catherinepereira.mcdrone.entity.DroneEntity;
import com.catherinepereira.mcdrone.task.TaskKind;
import java.util.List;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One drone's side of the client: its simulated flight, task scoring, tool state, resets, and the bridge session flying it.
 * The active drone always has a run, other drones have one while they work
 */
public final class DroneRun {
	final DroneController controller;
	final TaskScorer task = new TaskScorer();
	final ToolState tools = new ToolState();
	final ResetFlow resets;
	// tool packets waiting on the server's answer, by seq
	final TreeMap<Integer, List<Runnable>> syncWaiters = new TreeMap<>();
	int selectedSlot;
	long oneShotReadyMs;
	// the job that just ended, the next tick starts the drone's next queued job or sends it home
	@Nullable TaskKind finishedJob;
	boolean finishedJobSucceeded;
	// the bridge client flying this drone, null when nobody is
	@Nullable Session session;
	private DroneAction bridgeAction = DroneAction.ZERO;

	DroneRun(ClientRuntime runtime, DroneController controller) {
		this.controller = controller;
		this.resets = new ResetFlow(runtime, this);
	}

	public int droneId() {
		return this.controller.droneId();
	}

	public @Nullable DroneEntity drone() {
		return this.controller.drone();
	}

	public TaskScorer task() {
		return this.task;
	}

	/** Flying, starting, or finishing something, or held by a bridge client */
	boolean busy() {
		return this.session != null || this.resets.active() || this.task.active() || this.finishedJob != null;
	}

	void setBridgeAction(DroneAction action) {
		this.bridgeAction = action;
	}

	/** The realtime action for this tick. One-shot tool parts are dropped for the ticks after, so a place doesn't repeat */
	DroneAction nextBridgeAction() {
		DroneAction now = this.bridgeAction;
		this.bridgeAction = this.bridgeAction.continued();
		return now;
	}
}
