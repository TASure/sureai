# Agent Orchestration

> English page · Chinese sources: [../agent.md](../agent.md) and [../agent-advanced.md](../agent-advanced.md). Module: `sure-ai-agent`.

On top of the chat / Function Calling primitives in `sure-ai-core`, sureai provides a
lightweight ReAct multi-tool loop. The module depends only on `sure-ai-core`, so any
client implementing `AiClient` can drive it.

## ReAct in one picture

```
question → model Thought → needs a tool?
   yes → Action (tool_calls) → execute → Observation fed back → loop
   no  → final answer
```

The orchestrator runs the Thought → Action → Observation loop automatically: when the
model returns `tool_calls`, it executes them and feeds the results back as `tool`
messages until a plain-text answer comes out. Built-in guards are `maxIterations`
(default 10) and total `timeout` (default 120s).

## Minimal example

```java
import java.util.List;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiClient;

ToolRegistry registry = new ToolRegistry();
registry.register(ToolFunction.of("get_weather", "Get the weather for a city",
        """
        {"type":"object","required":["city"],
         "properties":{"city":{"type":"string"}}}
        """),
        args -> {
            String city = args.getString("city");
            return city + ": sunny 26C";
        });

ChatRequest base = ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.system("You are an assistant that uses tools.")))
        .build();

ReActAgent agent = new ReActAgent(client, base, registry);
String answer = agent.run("How is the weather in Xi'an?");
System.out.println(answer);
```

The handler receives an already-parsed `JsonObject` (`getString` / `getInt`) and
returns plain text that is fed back as a `tool` message. A handler exception does not
abort the loop — the error text goes back to the model so it can self-correct.

## Built-in tools

`com.sure.ai.agent.tool.builtin` ships ready handlers you can register directly:

- `CalculatorTool` — whitelist arithmetic `+ - * / ( )`, no eval / reflection / ScriptEngine.
- `DateTimeTool` — current date/time with configurable format and zone.
- `HttpTool` — JDK HttpClient GET/POST with http/https scheme whitelist and response truncation.

## Four production capabilities (v1.7.0)

All four are **optional injections** — pass `null` to disable, and behavior is
byte-identical to older versions.

| Capability | Package | What it does |
|---|---|---|
| **Checkpoint persistence** | `com.sure.ai.agent.checkpoint` | Snapshot history + iteration count per run; after a crash/restart, `AgentCheckpointer.resume(store, sessionId, agent)` replays from the breakpoint without repeating completed actions. Built on `InMemoryCheckpointStore` or `FileCheckpointStore`. |
| **Streaming events** | `com.sure.ai.agent.event` | `StreamingAgentListener` bridges `AgentListener` callbacks into structured `AgentEvent`s (thought / tool.called / tool.completed / final.answer / error), broadcast to many subscribers and writable as SSE frames via `AgentEventSseWriter`. |
| **HITL approval** | `com.sure.ai.agent.approval` | An `ApprovalGate` runs before a tool executes: an `ApprovalPolicy` decides whether approval is needed, an `ApprovalHandler` blocks until APPROVED / REJECTED / TIMEOUT. Rejection/timeout text is fed back to the model. |
| **Long-term memory** | `com.sure.ai.agent.memory.longterm` | Before a run, recall Top-K related facts into a system message; after the run, auto-extract the turn into long-term memory. Vector search with text-substring fallback. |

Combine all four in the 11-argument constructor (see [../agent-advanced.md](../agent-advanced.md)):

```java
ReActAgent agent = new ReActAgent(client, baseRequest, registry,
        listener,
        ReActAgent.DEFAULT_MAX_ITERATIONS, ReActAgent.DEFAULT_TIMEOUT,
        /*memory*/ null,
        /*checkpointStore*/ checkpointStore, /*sessionId*/ "sess-001",
        /*approvalGate*/ gate,
        /*longTermMemory*/ ltm);
```

## Platform compatibility

The module talks only to the `AiClient` interface. Protocol differences (OpenAI
`tools`, Qwen `functions`, Zhipu `tools`, Doubao `tools`, etc.) are handled in each
platform client's serialization layer — any Function-Calling-capable client drives
`ReActAgent` unchanged.

## Next steps

- [Gateway](./gateway.md) · [RAG](./rag.md) · [Structured output](./structured-output.md)
