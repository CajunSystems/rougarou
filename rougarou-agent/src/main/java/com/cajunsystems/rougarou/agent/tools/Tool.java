package com.cajunsystems.rougarou.agent.tools;

/**
 * A tool the agent can invoke.
 *
 * <p>Tool implementations should be:
 * <ul>
 *   <li>idempotent if at all possible — the harness may retry on transient failures</li>
 *   <li>fast or async-friendly — the worker dispatching tools runs on virtual threads but it's
 *       still considerate to bound execution time</li>
 *   <li>side-effect-honest — declare any externally visible effects in {@link #description()}</li>
 * </ul>
 */
public interface Tool {

    String name();

    String description();

    /** JSON schema describing the tool's input arguments. Used by LLMs that support tool use. */
    String inputSchemaJson();

    /** Invoke the tool with a JSON-encoded argument object; return the JSON-encoded result. */
    String invoke(String argsJson) throws ToolException;
}
