package com.cajunsystems.rougarou.agent.skills;

import com.cajunsystems.rougarou.agent.tools.Tool;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Registry of {@link Skill skills} that can be activated per session. */
public final class SkillRegistry {

    private final Map<String, Skill> skills = Collections.synchronizedMap(new LinkedHashMap<>());

    public SkillRegistry register(Skill skill) {
        skills.put(skill.name(), skill);
        return this;
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Collection<Skill> all() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /**
     * Return a flat tool registry containing every tool from the named skills.
     * Skills earlier in the list win on tool-name conflicts.
     */
    public ToolRegistry materializeTools(List<String> skillNames) {
        ToolRegistry registry = new ToolRegistry();
        Set<String> seen = new LinkedHashSet<>();
        for (String skillName : skillNames) {
            Skill skill = skills.get(skillName);
            if (skill == null) continue;
            for (Tool tool : skill.tools()) {
                if (seen.add(tool.name())) {
                    registry.register(tool);
                }
            }
        }
        return registry;
    }
}
