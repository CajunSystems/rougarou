package com.cajunsystems.rougarou.client.sdk;

import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.gumbo.api.SharedLog;
import com.cajunsystems.rougarou.agent.skills.SkillRegistry;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;
import com.cajunsystems.rougarou.agent.worker.AgentWorker;
import com.cajunsystems.rougarou.agent.worker.AgentWorkerConfig;
import com.cajunsystems.rougarou.agent.worker.ToolWorker;
import com.cajunsystems.rougarou.agent.worker.ToolWorkerConfig;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.gateway.controller.GatewayController;
import com.cajunsystems.rougarou.gateway.memory.AgentMemory;
import com.cajunsystems.rougarou.gateway.scheduler.ScheduleWorker;
import com.cajunsystems.rougarou.gateway.scheduler.Scheduler;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;
import com.cajunsystems.rougarou.gateway.session.SessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The fluent embedded SDK for the rougarou harness.
 *
 * <p>Bundles the gateway controller, the session manager, and any number of agent and tool
 * workers running in the same JVM. Users are free to spread workers across processes — they all
 * coordinate via the gumbo shared log.
 */
public final class RougarouClient implements AutoCloseable {

    private final SharedLog sharedLog;
    private final boolean ownsSharedLog;
    private final BayouSystem bayouSystem;
    private final boolean ownsBayouSystem;
    private final RougarouLog log;
    private final SessionManager sessionManager;
    private final GatewayController controller;
    private final List<AgentWorker> agentWorkers;
    private final List<ToolWorker> toolWorkers;
    private final Scheduler scheduler;
    private final ScheduleWorker scheduleWorker;

    private RougarouClient(Builder b) {
        this.sharedLog = b.sharedLog;
        this.ownsSharedLog = b.ownsSharedLog;
        this.bayouSystem = b.bayouSystem != null ? b.bayouSystem : new BayouSystem(sharedLog);
        this.ownsBayouSystem = b.bayouSystem == null;
        this.log = new RougarouLog(sharedLog);
        this.sessionManager = new SessionManager(bayouSystem, log);
        this.controller = new GatewayController(sessionManager, log);
        this.agentWorkers = new ArrayList<>(b.agentWorkers);
        this.toolWorkers = new ArrayList<>(b.toolWorkers);
        this.scheduler = new Scheduler(log);
        this.scheduleWorker = b.runScheduleWorker ? new ScheduleWorker(log) : null;
        for (var w : agentWorkers) w.start();
        for (var w : toolWorkers) w.start();
        if (scheduleWorker != null) scheduleWorker.start();
    }

    public GatewayController gateway() { return controller; }

    public AgentMemory memory() { return controller.memory(); }

    public RougarouLog log() { return log; }

    public BayouSystem bayou() { return bayouSystem; }

    public String openSession(SessionConfig config) { return controller.openSession(config); }

    public void resumeSession(String sessionId, SessionConfig config) { controller.resumeSession(sessionId, config); }

    public CompletableFuture<String> send(String sessionId, String content) {
        return controller.send(sessionId, content);
    }

    public CompletableFuture<Void> close(String sessionId, String reason) {
        return controller.close(sessionId, reason);
    }

    /** API for requesting future deliveries. Always available; needs an enabled worker to fire. */
    public Scheduler scheduler() { return scheduler; }

    @Override
    public void close() {
        for (var w : agentWorkers) w.close();
        for (var w : toolWorkers) w.close();
        if (scheduleWorker != null) scheduleWorker.close();
        sessionManager.close();
        if (ownsBayouSystem) bayouSystem.close();
        if (ownsSharedLog) {
            try { sharedLog.close(); } catch (Exception ignored) {}
        }
    }

    public static Builder builder(SharedLog sharedLog) {
        Builder b = new Builder();
        b.sharedLog = sharedLog;
        b.ownsSharedLog = false;
        return b;
    }

    /** Builder that takes ownership of the supplied shared log and closes it on shutdown. */
    public static Builder builderOwning(SharedLog sharedLog) {
        Builder b = new Builder();
        b.sharedLog = sharedLog;
        b.ownsSharedLog = true;
        return b;
    }

    public static final class Builder {
        private SharedLog sharedLog;
        private boolean ownsSharedLog;
        private BayouSystem bayouSystem;
        private boolean runScheduleWorker;
        private final List<AgentWorker> agentWorkers = new ArrayList<>();
        private final List<ToolWorker> toolWorkers = new ArrayList<>();

        /** Reuse an existing bayou system instead of letting the client create its own. */
        public Builder bayouSystem(BayouSystem v) { this.bayouSystem = v; return this; }

        /**
         * Run a {@link ScheduleWorker} in this process. At least one process backed by the same
         * shared log must do this for {@link Scheduler} requests to actually fire.
         */
        public Builder runScheduleWorker() { this.runScheduleWorker = true; return this; }

        /** Add an in-process agent worker; multiple may be added for parallel inference. */
        public Builder addAgentWorker(AgentWorkerConfig config) {
            agentWorkers.add(new AgentWorker(config, new RougarouLog(sharedLog)));
            return this;
        }

        /** Add an in-process tool worker. */
        public Builder addToolWorker(ToolWorkerConfig config) {
            toolWorkers.add(new ToolWorker(config, new RougarouLog(sharedLog)));
            return this;
        }

        public Builder addAgentWorker(AgentWorker worker) { agentWorkers.add(worker); return this; }

        public Builder addToolWorker(ToolWorker worker) { toolWorkers.add(worker); return this; }

        public RougarouClient build() {
            if (sharedLog == null) throw new IllegalStateException("sharedLog must be set");
            return new RougarouClient(this);
        }
    }

    public ToolRegistry tools() { return new ToolRegistry(); }

    public SkillRegistry skills() { return new SkillRegistry(); }
}
