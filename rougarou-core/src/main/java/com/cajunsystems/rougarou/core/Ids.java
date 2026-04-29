package com.cajunsystems.rougarou.core;

import java.util.UUID;

/** Helpers for generating short, sortable-ish ids used across rougarou layers. */
public final class Ids {

    private Ids() {}

    public static String newSessionId() {
        return "ses-" + shortUuid();
    }

    public static String newMessageId() {
        return "msg-" + shortUuid();
    }

    public static String newRequestId() {
        return "req-" + shortUuid();
    }

    public static String newToolCallId() {
        return "tc-" + shortUuid();
    }

    /**
     * Deterministic id for the synthetic UserMessage emitted when a schedule fires. Tying the
     * messageId to the scheduleId means two scheduler workers racing to fire the same schedule
     * produce two byte-identical UserMessage events, which the session actor then deduplicates.
     */
    public static String scheduledUserMessageId(String scheduleId) {
        return "msg-fire-" + scheduleId;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
