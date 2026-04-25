package com.cajunsystems.rougarou.agent.worker;

import com.cajunsystems.rougarou.agent.llm.LlmClient;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;

import java.time.Duration;
import java.util.Objects;

/** Builder-friendly configuration for {@link AgentWorker}. */
public record AgentWorkerConfig(
        String workerId,
        String model,
        LlmClient llmClient,
        ToolRegistry toolRegistry,
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier
) {

    public AgentWorkerConfig {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(llmClient, "llmClient");
        toolRegistry = toolRegistry == null ? new ToolRegistry() : toolRegistry;
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        initialBackoff = initialBackoff == null ? Duration.ofMillis(250) : initialBackoff;
        if (backoffMultiplier < 1.0) throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String workerId = "agent-worker";
        private String model = "echo";
        private LlmClient llmClient;
        private ToolRegistry toolRegistry;
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(250);
        private double backoffMultiplier = 2.0;

        public Builder workerId(String v) { this.workerId = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder llmClient(LlmClient v) { this.llmClient = v; return this; }
        public Builder toolRegistry(ToolRegistry v) { this.toolRegistry = v; return this; }
        public Builder maxAttempts(int v) { this.maxAttempts = v; return this; }
        public Builder initialBackoff(Duration v) { this.initialBackoff = v; return this; }
        public Builder backoffMultiplier(double v) { this.backoffMultiplier = v; return this; }

        public AgentWorkerConfig build() {
            return new AgentWorkerConfig(workerId, model, llmClient, toolRegistry,
                    maxAttempts, initialBackoff, backoffMultiplier);
        }
    }
}
