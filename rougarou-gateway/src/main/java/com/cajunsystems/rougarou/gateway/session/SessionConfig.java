package com.cajunsystems.rougarou.gateway.session;

import com.cajunsystems.rougarou.agent.skills.SkillRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Per-session configuration used when creating a fresh session. */
public record SessionConfig(
        String agentId,
        String systemPrompt,
        List<String> activeSkills,
        Map<String, String> metadata,
        SkillRegistry skillRegistry
) {

    public SessionConfig {
        Objects.requireNonNull(agentId, "agentId");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        activeSkills = activeSkills == null ? List.of() : List.copyOf(activeSkills);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String agentId = "default";
        private String systemPrompt = "";
        private List<String> activeSkills = List.of();
        private Map<String, String> metadata = Map.of();
        private SkillRegistry skillRegistry;

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder activeSkills(List<String> v) { this.activeSkills = v; return this; }
        public Builder metadata(Map<String, String> v) { this.metadata = v; return this; }
        public Builder skillRegistry(SkillRegistry v) { this.skillRegistry = v; return this; }

        public SessionConfig build() {
            return new SessionConfig(agentId, systemPrompt, activeSkills, metadata, skillRegistry);
        }
    }
}
