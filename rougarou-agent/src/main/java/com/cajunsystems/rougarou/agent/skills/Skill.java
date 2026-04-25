package com.cajunsystems.rougarou.agent.skills;

import com.cajunsystems.rougarou.agent.tools.Tool;

import java.util.List;

/**
 * A skill bundles a set of tools and an instructional prompt fragment that primes the agent on
 * how and when to use them. Skills can be loaded individually per session.
 *
 * <p>This mirrors Claude's "skills" concept: composable, narrowly-scoped capabilities that can be
 * activated or deactivated per session without redefining the agent.
 */
public interface Skill {

    String name();

    String description();

    /** Prompt fragment appended to the system prompt when this skill is active. */
    String promptFragment();

    /** Tools this skill makes available. */
    List<Tool> tools();
}
