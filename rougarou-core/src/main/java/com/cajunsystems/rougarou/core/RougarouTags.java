package com.cajunsystems.rougarou.core;

import com.cajunsystems.gumbo.core.LogTag;

/**
 * Well-known {@link LogTag log tags} used by the rougarou harness.
 *
 * <p>Rougarou layers everything on top of gumbo's tagged shared log. Each layer reads from
 * and writes to a small set of namespaces:
 *
 * <ul>
 *   <li>{@code rougarou.session:&lt;sessionId&gt;} – per-session conversation history (event-sourced)</li>
 *   <li>{@code rougarou.sessions} – cross-session lifecycle stream (creates / closes)</li>
 *   <li>{@code rougarou.inference} – pending inference work picked up by agent workers</li>
 *   <li>{@code rougarou.tool} – pending tool invocations picked up by tool workers</li>
 *   <li>{@code rougarou.audit} – every event is fan-out tagged here for global observability</li>
 * </ul>
 */
public final class RougarouTags {

    public static final String SESSION_NS = "rougarou.session";
    public static final String SESSIONS_NS = "rougarou.sessions";
    public static final String INFERENCE_NS = "rougarou.inference";
    public static final String TOOL_NS = "rougarou.tool";
    public static final String SCHEDULE_NS = "rougarou.schedule";
    public static final String AUDIT_NS = "rougarou.audit";

    private RougarouTags() {}

    public static LogTag session(String sessionId) {
        return LogTag.of(SESSION_NS, sessionId);
    }

    public static LogTag sessions() {
        return LogTag.of(SESSIONS_NS);
    }

    public static LogTag inferenceTasks() {
        return LogTag.of(INFERENCE_NS);
    }

    public static LogTag toolTasks() {
        return LogTag.of(TOOL_NS);
    }

    public static LogTag schedule() {
        return LogTag.of(SCHEDULE_NS);
    }

    public static LogTag audit() {
        return LogTag.of(AUDIT_NS);
    }
}
