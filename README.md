# Rougarou

**A reliable, scalable agentic harness framework built on the [gumbo](https://github.com/CajunSystems/gumbo) shared log.**

Rougarou is a Java 21+ harness for running LLM agents — like an open-source Claude Code or
"open claw" — but with the persistence and recovery properties of a log-structured system.
Every user turn, inference request, tool call, and result is appended to a single shared log,
so any layer can crash and resume from the log without losing a session.

It composes three CajunSystems libraries:

- **[gumbo](https://github.com/CajunSystems/gumbo)** — the shared append-only log; source of truth.
- **[bayou](https://github.com/CajunSystems/bayou)** — actor system on top of gumbo; one actor per session.
- **[boudin](https://github.com/CajunSystems/boudin)** — durable workflow / scheduler primitives.

---

## Architecture

Rougarou splits cleanly into three layers, each backed by a small set of log tags:

```
 ┌─────────────────────────────────────────────────────────────┐
 │ Client layer (rougarou-client)                              │
 │   • RougarouClient SDK                                      │
 │   • RougarouHttpServer  (JDK HttpServer + virtual threads)  │
 └─────────────────────────────────────────────────────────────┘
                             │
                             ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ Gateway layer (rougarou-gateway)                            │
 │   • SessionManager   – one bayou actor per session          │
 │   • SessionActor     – serializes per-session decisions     │
 │   • AgentMemory      – read-only projection from the log    │
 │   • GatewayController – fluent façade for the client layer  │
 └─────────────────────────────────────────────────────────────┘
                             │
                             ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ Agent layer (rougarou-agent)                                │
 │   • AgentWorker      – calls the LLM, retries, persists     │
 │   • ToolWorker       – dispatches tool calls, retries       │
 │   • LlmClient / Tool / Skill abstractions                   │
 └─────────────────────────────────────────────────────────────┘
                             │
                             ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ rougarou-core  +  gumbo (shared log)                        │
 │   tags: rougarou.session:<id>, rougarou.sessions,           │
 │         rougarou.inference, rougarou.tool, rougarou.audit   │
 └─────────────────────────────────────────────────────────────┘
```

### Why a shared log?

* **Single source of truth.** Session state, agent decisions, tool calls and results all live in
  the same log under predictable tags. There is no separate database.
* **Crash-safe by design.** Restart the gateway, the agent worker, or the tool worker — each
  rebuilds its world by replaying its log tag from a checkpoint.
* **Horizontally scalable.** Run multiple agent workers and tool workers; gumbo gives them total
  ordering and exclusive seqnums so work isn't double-processed.
* **Observable.** Every event is fanned out to `rougarou.audit`. Tail it for a complete audit
  trail of every conversation, decision, and tool effect.

### Event flow for one user turn

```
client.send(sid, "hello")
   │
   ▼ UserMessage                  ──► rougarou.session:<sid>, rougarou.audit
SessionActor sees UserMessage
   │
   ▼ InferenceRequested           ──► rougarou.session:<sid>, rougarou.inference, rougarou.audit
AgentWorker picks it up, calls LLM
   │
   ▼ InferenceCompleted           ──► rougarou.session:<sid>, rougarou.audit
SessionActor sees response;
if it has tool calls:
   ▼ ToolRequested(s)             ──► rougarou.session:<sid>, rougarou.tool, rougarou.audit
   ToolWorker invokes the tool
   ▼ ToolCompleted / ToolFailed   ──► rougarou.session:<sid>, rougarou.audit
   (loops back to InferenceRequested with the tool result in the snapshot)
otherwise:
   ▼ AssistantMessage             ──► rougarou.session:<sid>, rougarou.audit
   client.send() future resolves with the assistant text
```

Every event in this flow is durable. The `SessionActor`'s only durable state is the session log
tag — kill it, respawn it, and `preStart` replays the tag back into in-memory `SessionState`.

---

## Modules

| Module             | What it owns                                                |
| ------------------ | ----------------------------------------------------------- |
| `rougarou-core`    | Sealed `RougarouEvent` hierarchy, log tags, Kryo serializer, `RougarouLog` facade |
| `rougarou-agent`   | `LlmClient`, `Tool`, `Skill`, `AgentWorker`, `ToolWorker`     |
| `rougarou-gateway` | `SessionManager`, `SessionActor`, `AgentMemory`, `GatewayController` |
| `rougarou-client`  | `RougarouClient` SDK, `RougarouHttpServer`                    |
| `rougarou-examples`| `EchoChatExample`, `HttpExample`, sample skill              |

---

## Getting started

### Prerequisites

* JDK 21+
* Gradle 8.x

### Run the example

```bash
gradle :rougarou-examples:run
```

Expected output (abridged):

```
session: ses-7143f71978de
user → Hello there!
assistant → Echo: Hello there!
user → tool:echo {"text":"loop test"}
assistant → Tool completed: [tool_result:echo id=... {"echo":"loop test"}]
```

### Run the HTTP example

```bash
gradle :rougarou-examples:run -PmainClass=com.cajunsystems.rougarou.examples.HttpExample
```

Or just compile and run the jar:

```bash
gradle :rougarou-examples:installDist
./rougarou-examples/build/install/rougarou-examples/bin/rougarou-examples
```

Then in another terminal:

```bash
SID=$(curl -s -X POST http://localhost:8080/sessions | jq -r .sessionId)
curl -s -X POST http://localhost:8080/sessions/$SID/messages \
     -H 'content-type: application/json' \
     -d '{"content":"Hello"}' | jq
curl -s http://localhost:8080/sessions/$SID/conversation | jq
```

### Run the test suite

```bash
gradle test
```

Fifteen tests cover: event serialization, log fan-out routing, the `EchoLlmClient` decision tree,
and three end-to-end integration tests through the full client → gateway → agent → tool round-trip.

---

## Adding rougarou to your project

Until rougarou is published to a Maven repository, install the dependencies locally
and add the project as a Gradle composite build, or copy the modules into your repo.

The transitive dep chain you'll need:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.cajunsystems:rougarou-client:0.1.0-SNAPSHOT")
    // pulls in rougarou-gateway, rougarou-agent, rougarou-core, gumbo, bayou, boudin
}
```

---

## Embedded usage (SDK)

```java
import com.cajunsystems.gumbo.persistence.FileBasedPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.llm.EchoLlmClient;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;
import com.cajunsystems.rougarou.agent.worker.AgentWorkerConfig;
import com.cajunsystems.rougarou.agent.worker.ToolWorkerConfig;
import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;

var sharedLog = SharedLogService.open(SharedLogConfig.builder()
        .persistenceAdapter(new FileBasedPersistenceAdapter("/var/data/rougarou"))
        .build());

ToolRegistry tools = new ToolRegistry();      // register your tools here

try (RougarouClient client = RougarouClient.builderOwning(sharedLog)
        .addAgentWorker(AgentWorkerConfig.builder()
                .workerId("agent-1")
                .llmClient(new EchoLlmClient())   // swap for AnthropicLlmClient etc.
                .toolRegistry(tools)
                .maxAttempts(3)
                .build())
        .addToolWorker(ToolWorkerConfig.builder()
                .workerId("tool-1")
                .toolRegistry(tools)
                .build())
        .build()) {

    String sid = client.openSession(SessionConfig.builder()
            .agentId("my-agent")
            .systemPrompt("You are a helpful assistant.")
            .build());

    String reply = client.send(sid, "What's the weather?")
            .get(60, TimeUnit.SECONDS);
    System.out.println(reply);
}
```

### What the SDK gives you

| Method                                   | What it does                                  |
| ---------------------------------------- | --------------------------------------------- |
| `client.openSession(config)`             | Spawn a new session actor; returns a sessionId |
| `client.resumeSession(sessionId, cfg)`   | Re-attach to a session that already exists in the log |
| `client.send(sessionId, text)`           | Send a user turn; future resolves with the assistant's final text |
| `client.close(sessionId, reason)`        | Append `SessionClosed` and stop the actor    |
| `client.memory().conversation(sessionId)` | Read-only projection of user/assistant turns |
| `client.memory().history(sessionId)`     | Full event history including inference/tool plumbing |
| `client.log()`                           | Underlying `RougarouLog` for advanced use     |
| `client.bayou()`                         | Underlying bayou system if you need to spawn your own actors |

---

## Plugging in a real LLM

The `LlmClient` interface is provider-agnostic — implement it once for your provider:

```java
public final class AnthropicLlmClient implements LlmClient {
    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        // 1) Convert request.conversation() into Anthropic's message format
        // 2) Convert request.availableTools() into Anthropic's tool spec format
        // 3) Call the API; map response.stop_reason to a string
        // 4) Map content blocks → LlmResponse(text, toolCalls, stopReason)
    }
}
```

Pass it into `AgentWorkerConfig.llmClient(...)`. The worker handles retries, backoff, and event
persistence around your synchronous boundary, so the client itself can stay focused on the API
mapping.

---

## Adding tools and skills

A tool is anything that takes JSON in and returns JSON out:

```java
public final class WebSearchTool implements Tool {
    @Override public String name() { return "web_search"; }
    @Override public String description() { return "Search the web for a query."; }
    @Override public String inputSchemaJson() {
        return """
               {"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}
               """;
    }
    @Override public String invoke(String argsJson) throws ToolException {
        // run the search; return JSON
    }
}
```

A skill bundles tools with an instructional prompt fragment that primes the agent on when to use
them. Skills can be activated per session — perfect for opt-in capabilities:

```java
SkillRegistry skills = new SkillRegistry()
    .register(new WebSearchSkill())
    .register(new FileEditSkill());

ToolRegistry tools = skills.materializeTools(List.of("web_search", "file_edit"));

String sid = client.openSession(SessionConfig.builder()
    .agentId("research-agent")
    .systemPrompt("You research topics and write reports.")
    .activeSkills(List.of("web_search"))    // file_edit registered but not active here
    .skillRegistry(skills)
    .build());
```

---

## Reliability story

### Crash recovery

Every layer recovers by re-reading its log tag from a persisted checkpoint:

* **Gateway** – `SessionActor.preStart` calls `RougarouLog.readSession(id)` and folds events
  through `SessionState`. Then it subscribes to the tail and processes future events serially.
* **Agent worker** – stores a per-worker checkpoint at the key
  `rougarou.inference/checkpoint:<workerId>` via gumbo's tag-scoped KV store. On startup it
  resumes the subscription from `checkpoint + 1`.
* **Tool worker** – same pattern at `rougarou.tool/checkpoint:<workerId>`.

There's no shared state to corrupt. Stop any process at any time and restart it.

### Idempotency

Every inference and tool dispatch carries a `requestId` / `toolCallId`. Workers track which ids
are already in flight (in-memory `Set`) and the persistent checkpoint guarantees no replay of
already-acknowledged events. Tools are expected to be idempotent — surface non-idempotency in
the tool's `description()` so the LLM can avoid double calls.

### Retries

`AgentWorker` and `ToolWorker` both honor `maxAttempts`, `initialBackoff`, and `backoffMultiplier`.
Failures are persisted as `InferenceFailed` / `ToolFailed` events, so a downstream observer can
see exactly which retry attempts ran and why each failed.

### Multi-process scaling

Run gateway and worker processes on different machines pointed at the same gumbo backend. Use
`FileBasedPersistenceAdapter` for single-node durability or `FoundationDBPersistenceAdapter` for
true multi-node coordination. Sessions naturally partition by id; workers compete for inference
and tool tasks via gumbo's totally-ordered subscription.

---

## HTTP API

The bundled `RougarouHttpServer` is a tiny JDK-only HTTP shell — useful for poking at the system
or stitching together a UI in another language. All endpoints are JSON.

| Verb     | Path                                | Body / Query        | Response                    |
| -------- | ----------------------------------- | ------------------- | --------------------------- |
| `POST`   | `/sessions`                         | (none)              | `{"sessionId":"ses-..."}`   |
| `POST`   | `/sessions/{id}/messages`           | `{"content":"..."}` | `{"reply":"..."}`           |
| `GET`    | `/sessions/{id}/conversation`       | (none)              | `{"turns":[...]}`           |
| `DELETE` | `/sessions/{id}`                    | (none)              | `{"closed":true}`           |

Production deployments will likely want a heavier framework (auth, streaming, SSE) — replace
`RougarouHttpServer` with your own controller wrapping `GatewayController`.

---

## Event reference

All events implement the sealed `RougarouEvent` interface. Read the full
[events package](rougarou-core/src/main/java/com/cajunsystems/rougarou/core/events) for record
field details. Variants:

| Event                  | Direction      | Tags it lands on                                |
| ---------------------- | -------------- | ----------------------------------------------- |
| `SessionCreated`       | gateway → log  | `session`, `sessions`, `audit`                  |
| `SessionClosed`        | gateway → log  | `session`, `sessions`, `audit`                  |
| `UserMessage`          | gateway → log  | `session`, `audit`                              |
| `AssistantMessage`     | gateway → log  | `session`, `audit`                              |
| `InferenceRequested`   | gateway → log  | `session`, `inference`, `audit`                 |
| `InferenceCompleted`   | agent  → log   | `session`, `audit`                              |
| `InferenceFailed`      | agent  → log   | `session`, `audit`                              |
| `ToolRequested`        | gateway → log  | `session`, `tool`, `audit`                      |
| `ToolCompleted`        | tool   → log   | `session`, `audit`                              |
| `ToolFailed`           | tool   → log   | `session`, `audit`                              |
| `AgentError`           | any    → log   | `session`, `audit`                              |

---

## Project status

This is an early-stage research framework. The core model is in place and well-tested, but
production users will want to add:

* a real `LlmClient` for their provider of choice (Anthropic, OpenAI, local)
* a streaming response API on the SDK and HTTP server
* boudin-backed durable timers for things like idle session timeouts and scheduled agent runs
* metrics (Micrometer integration is straightforward through bayou and boudin)

PRs welcome.

---

## License

MIT — see [LICENSE](LICENSE).
