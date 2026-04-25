package com.cajunsystems.rougarou.examples;

import com.cajunsystems.rougarou.agent.skills.Skill;
import com.cajunsystems.rougarou.agent.tools.EchoTool;
import com.cajunsystems.rougarou.agent.tools.Tool;

import java.util.List;

/** A trivial skill that surfaces the {@link EchoTool} with a primer prompt. */
public final class EchoSkill implements Skill {

    private final List<Tool> tools = List.of(new EchoTool());

    @Override
    public String name() { return "echo"; }

    @Override
    public String description() { return "Echo the user's text back to them."; }

    @Override
    public String promptFragment() {
        return "When the user asks you to echo something, call the `echo` tool with the text.";
    }

    @Override
    public List<Tool> tools() { return tools; }
}
