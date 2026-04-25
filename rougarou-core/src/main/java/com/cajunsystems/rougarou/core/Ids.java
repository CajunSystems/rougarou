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

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
