# Rougarou

**A reliable, scalable agentic harness framework built on the [gumbo](https://github.com/CajunSystems/gumbo) shared log.**

Rougarou is a Java 21+ harness for running LLM agents — like an open-source Claude Code or
"open claw" — but with the persistence and recovery properties of a log-structured system.
Every user turn, inference request, tool call, and result is appended to a single shared log,
so any layer can crash and resume from the log without losing a session.

It composes two CajunSystems libraries:

- **[gumbo](https://github.com/CajunSystems/gumbo)** — the shared append-only log; source of truth.
- **[bayou](https://github.com/CajunSystems/bayou)** — actor system on top of gumbo; one actor per session.

A third library, **[boudin](https://github.com/CajunSystems/boudin)** (durable workflows), is a
natural extension point — see [When to reach for boudin](#when-to-reach-for-boudin-instead) below.
It is not a current dependency.

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
 │   • Scheduler / ScheduleWorker – future-dated user turns    │
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
 │         rougarou.inference, rougarou.tool,                  │
 │         rougarou.schedule, rougarou.audit                   │
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
| `rougarou-gateway` | `SessionManager`, `SessionActor`, `AgentMemory`, `GatewayController`, `Scheduler`, `ScheduleWorker` |
| `rougarou-client`  | `RougarouClient` SDK, `RougarouHttpServer`                    |
| `rougarou-examples`| `EchoChatExample`, `HttpExample`, `ScheduledChatExample`, sample skill |

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
    // pulls in rougarou-gateway, rougarou-agent, rougarou-core, gumbo, bayou
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

## Scheduling future deliveries

Rougarou ships a small **gumbo-native scheduler** for one-shot delayed deliveries — useful for
idle-session pings, "remind me in 10 minutes" style follow-ups, or any case where you want a
synthetic user turn to land at a future time.

The scheduler is just three events on a dedicated `rougarou.schedule` tag:

| Event              | Producer            | Effect                                       |
| ------------------ | ------------------- | -------------------------------------------- |
| `ScheduleRequested`| `Scheduler` API     | enqueues a future fire (`fireAt`, `payload`) |
| `ScheduleCancelled`| `Scheduler.cancel`  | finalizes a `scheduleId` so it never fires   |
| `ScheduleFired`    | `ScheduleWorker`    | finalizes a `scheduleId` and delivers the payload |

A `ScheduleWorker` subscribes to the schedule tag, replays it to rebuild an in-memory priority
queue keyed by `fireAt`, and parks a virtual thread on the next due entry. When it fires it
appends `ScheduleFired` (which lands on both the schedule tag and the target session tag) and a
`UserMessage` carrying the payload. The session actor receives the `UserMessage` like any other
user turn and triggers inference.

### Usage

```java
try (RougarouClient client = RougarouClient.builder(sharedLog)
        .runScheduleWorker()                 // <-- enable a worker in this process
        .addAgentWorker(...)
        .build()) {

    String sid = client.openSession(SessionConfig.builder().agentId("a").build());

    // After 10 minutes, deliver "are you still there?" as a user turn — the agent will
    // respond into the session log just as if a real user typed it.
    String scheduleId = client.scheduler()
            .scheduleUserInputAfter(sid, Duration.ofMinutes(10), "are you still there?")
            .get();

    // Or at an absolute instant:
    client.scheduler().scheduleUserInputAt(sid, Instant.parse("2026-12-25T09:00:00Z"),
            "merry christmas");

    // Cancel before it fires:
    client.scheduler().cancel(sid, scheduleId, "user-disconnected");
}
```

### Reliability

* The schedule tag is the source of truth. Restart the worker (or run it on a new node) and it
  rebuilds the queue from the log on startup; entries that already have a matching
  `ScheduleFired` or `ScheduleCancelled` are skipped.
* Multiple `ScheduleWorker` processes can run side by side. Each independently rebuilds a queue;
  the first to write `ScheduleFired` for a given `scheduleId` wins, and peers see it on their
  subscription and skip it. Worst-case under racing peers is one extra fire if both write before
  observing the other — keep tools idempotent (which they should be anyway).
* Granularity is whatever the JVM scheduler gives you — typically sub-millisecond accuracy. The
  worker uses `Condition.awaitNanos` so a sooner-than-current-head request wakes it up immediately.

### When to reach for boudin instead

The native scheduler is intentionally small — a queue of one-shot fires. For anything more
involved, [boudin](https://github.com/CajunSystems/boudin) workflows are the natural next layer:

* **recurring agent runs (cron-style)** — `Workflow.sleep(Duration.ofHours(24))` inside a loop
* **multi-step durable plans** — each step is an activity with its own retry/timeout policy,
  state survives process restarts
* **long-running coordination** — multi-day reminders, subscription cancellations, anything
  that outlives a single process

Boudin is *not* currently a rougarou dependency. The simple stateless workers used here
(inference and tool execution) wouldn't gain anything from being workflows — wrap them only
when you have actual orchestration to express. Add `com.cajunsystems:boudin:0.1.0` to your
project, register a `Worker` against the same shared log, and have your activities call into
`RougarouClient` to drive sessions.

---

## HTTP API

The bundled `RougarouHttpServer` is a small Javalin-on-Jetty front end (with virtual threads and
Jackson JSON) — useful for poking at the system or stitching together a UI in another language.
All endpoints are JSON.

| Verb     | Path                                | Body / Query        | Response                    |
| -------- | ----------------------------------- | ------------------- | --------------------------- |
| `POST`   | `/sessions`                         | (none)              | `{"sessionId":"ses-..."}`   |
| `POST`   | `/sessions/{id}/messages`           | `{"content":"..."}` | `{"reply":"..."}`           |
| `GET`    | `/sessions/{id}/conversation`       | (none)              | `{"turns":[...]}`           |
| `DELETE` | `/sessions/{id}`                    | (none)              | `{"closed":true}`           |

`server.app()` exposes the underlying `Javalin` instance so callers can register additional
routes / middleware (auth, observability, custom endpoints) without forking the file. For
streaming responses, SSE, or websockets, build directly on the Javalin instance —
`GatewayController.send()` returns the `CompletableFuture` you'll want to wire up.

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
| `ScheduleRequested`    | client → log   | `session`, `schedule`, `audit`                  |
| `ScheduleCancelled`    | client → log   | `schedule`, `audit`                             |
| `ScheduleFired`        | scheduler → log| `session`, `schedule`, `audit`                  |

---

## Project status

This is an early-stage research framework. The core model is in place and well-tested, but
production users will want to add:

* a real `LlmClient` for their provider of choice (Anthropic, OpenAI, local)
* a streaming response API on the SDK and HTTP server
* boudin-backed durable workflows for cron-style recurring agents and multi-step plans
* metrics (Micrometer integration is straightforward through bayou)

PRs welcome.

---

## License

MIT — see [LICENSE](LICENSE).
