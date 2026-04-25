package com.cajunsystems.rougarou.agent.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Thread-safe registry of {@link Tool tools} the agent layer can dispatch. */
public final class ToolRegistry {

    private final Map<String, Tool> tools = Collections.synchronizedMap(new LinkedHashMap<>());

    public ToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }
}
