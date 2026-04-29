package com.cajunsystems.rougarou.core.events;

/** Discriminator for what a fired schedule should do when its turn arrives. */
public enum ScheduleKind {
    /** The payload is treated as the content of a synthetic user turn delivered to the session. */
    USER_INPUT
}
