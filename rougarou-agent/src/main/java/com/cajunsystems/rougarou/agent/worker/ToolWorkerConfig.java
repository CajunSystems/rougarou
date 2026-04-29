package com.cajunsystems.rougarou.agent.worker;

import com.cajunsystems.rougarou.agent.tools.ToolRegistry;

import java.time.Duration;
import java.util.Objects;

public record ToolWorkerConfig(
        String workerId,
        ToolRegistry toolRegistry,
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier
) {

    public ToolWorkerConfig {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(toolRegistry, "toolRegistry");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        initialBackoff = initialBackoff == null ? Duration.ofMillis(250) : initialBackoff;
        if (backoffMultiplier < 1.0) throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String workerId = "tool-worker";
        private ToolRegistry toolRegistry;
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(250);
        private double backoffMultiplier = 2.0;

        public Builder workerId(String v) { this.workerId = v; return this; }
        public Builder toolRegistry(ToolRegistry v) { this.toolRegistry = v; return this; }
        public Builder maxAttempts(int v) { this.maxAttempts = v; return this; }
        public Builder initialBackoff(Duration v) { this.initialBackoff = v; return this; }
        public Builder backoffMultiplier(double v) { this.backoffMultiplier = v; return this; }

        public ToolWorkerConfig build() {
            return new ToolWorkerConfig(workerId, toolRegistry, maxAttempts, initialBackoff, backoffMultiplier);
        }
    }
}
