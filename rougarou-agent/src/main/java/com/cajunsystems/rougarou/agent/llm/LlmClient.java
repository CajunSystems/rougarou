package com.cajunsystems.rougarou.agent.llm;

/**
 * Provider-agnostic LLM client.
 *
 * <p>Implementations may call Anthropic, OpenAI, a local model, or be a deterministic stub for
 * tests. The agent worker is the only place this is invoked from, so all retries, timeouts, and
 * persistence are handled by the worker around this synchronous boundary.
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request) throws LlmException;
}
