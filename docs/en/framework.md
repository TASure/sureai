# Declarative Orchestration (AiService / Advisor chain / Semantic Cache)

> English page · Chinese source: [../framework.md](../framework.md).

sureai adds a declarative orchestration layer in **v2.5.0** (`sure-ai-framework`): a
**Java interface annotated once becomes a callable AI service** via a JDK dynamic proxy, and an
ordered **Advisor chain** cross-cuts request/response (cache short-circuit, logging, tool loop,
structured-output self-correction). Positioned against LangChain4j `AiServices` and Spring AI's
Advisor chain. The module depends only on `sure-ai-core` — zero third-party runtime deps.

## Quick start

One line turns an annotated interface into a service:

```java
@AiService(model = "gpt-4o-mini")
interface Assistant {
    @SystemMessage("You are a {role} assistant")
    @UserMessage("Summarize: {text}")
    String summarize(String role, String text);
}

// client is any platform AiClient (OpenAI / DeepSeek / Doubao ...)
Assistant ai = FrameworkUtil.create(Assistant.class, client);
String result = ai.summarize("legal", "the contract body ...");
```

Model resolution order: `FrameworkUtil.builder(...).model(x)` overrides `@AiService(model=...)`;
if neither is set, proxy creation throws `AiException`.

## The annotation family

| Annotation | Target | Meaning |
|---|---|---|
| `@AiService` | interface (TYPE) | default `model` and sampling `temperature` (`-1` = unset) |
| `@SystemMessage` | method | system-message template, placed first |
| `@UserMessage` | method | user-message template (empty = fallback rule below) |
| `@Tool` | method | expose the method as a model-callable tool (auto JSON Schema) |
| `@Memory` | interface / method | enable history injection + write-back |
| `@Param` | parameter | bind a parameter to a `{placeholder}` name |

Templates use `{paramName}`, bound by parameter name (or `@Param`). Without `-parameters`
compile flag, use `@Param("who") String who` to back the `{who}` placeholder.

Return-type mapping:

| Return | Behavior |
|---|---|
| `String` | blocking, returns `ChatResponse.firstText()` |
| `ChatResponse` | blocking, raw response |
| `Stream<ChatStreamChunk>` | streaming (**bypasses the Advisor chain**) |
| `record` | auto-attaches `responseFormat=json_schema`, deserialized into the record |
| `void` / others | rejected at proxy creation |

## Tools (@Tool)

Annotate a method with `@Tool`; the proxy turns its signature into a `ToolSpec`
(method/param names → JSON Schema) and attaches it to `ChatRequest.tools`:

```java
interface Calc {
    @UserMessage("How is the weather today?")
    String ask(String q);

    @Tool(name = "add", description = "Sum two integers")
    default int add(int a, int b) { return a + b; }
}
```

**Direct calls are rejected** (the proxy throws `IllegalStateException` on `@Tool` methods);
the model triggers them. When the model returns `tool_calls`, `ToolCallingAdvisor` runs the loop
(see below) and the built-in `ReflectionToolExecutor` reflectively invokes the `@Tool` method and
feeds the result back. `ToolExecutor` is a pluggable SPI
(`String execute(String toolName, String argumentsJson)`) — plug in `sure-ai-agent`'s
`ToolRegistry` to avoid a framework→agent dependency.

## Memory

Inject a `ChatMemory` and annotate `@Memory`: history is injected before each blocking request and
the user/assistant turn is written back afterward.

```java
@AiService(model = "gpt-4o-mini")
@Memory
interface Chatbot { String talk(String msg); }

Chatbot ai = FrameworkUtil.builder(Chatbot.class, client)
        .memory(new InMemoryChatMemory(20))   // ring window, default 20 entries
        .build();
```

Built-in `InMemoryChatMemory` is a synchronized FIFO ring window (`new InMemoryChatMemory()` = 20
entries). Streaming does **not** write back memory.

## Advisor chain

Three hooks (registered order A, B, C):

- `before(ctx)` — **forward** A→B→C; may rewrite `ctx.messages()`.
- `around(chain, ctx)` — **nested**: A wraps B wraps C wraps the terminal. Default just
  `chain.proceed(ctx)`; override to call it zero times (cache short-circuit), once (rewrite), or
  **many times** (tool loop / validation retry).
- `after(ctx)` — **reverse** C→B→A, finally semantics (always runs).

`AdvisorContext` carries the mutable message list, `toolExecutor()`, `expectedType()` (the target
record for structured output), the response, and free-form `attribute(...)`; the terminal request is
rebuilt via `rebuildRequest()`.

**Recommended order** (pass to `builder.advisors(List.of(...))`):

```
SemanticCacheAdvisor → LoggingAdvisor → ToolCallingAdvisor → StructuredOutputValidationAdvisor
```

Cache outermost (short-circuit ASAP); logging outside the tool loop (one total-timing log per
orchestration); tool loop outside structured validation.

| Advisor | Constructor | Default | Role |
|---|---|---|---|
| `SemanticCacheAdvisor` | `(SemanticCache)` / `(SemanticCache, long ttlMillis)` | cache TTL | hit → short-circuit, no model call; miss → put back |
| `LoggingAdvisor` | `()` | — | `java.util.logging` summary of request/timing/tool count (no full prompt) |
| `ToolCallingAdvisor` | `()` / `(int maxIterations)` | `maxIterations=5` | auto tool loop until no `tool_calls` or cap |
| `StructuredOutputValidationAdvisor` | `()` / `(int maxRetries)` | `maxRetries=2` | validate JSON-vs-record; on mismatch, feed the error back for a rewrite |

All four are stateless and shareable across concurrent calls; per-call state lives in `AdvisorContext`.

## Semantic cache

Core's exact cache requires byte-identical requests. The semantic cache embeds the query and matches
by **cosine similarity**, so paraphrases reuse one cached response:

```java
SemanticCache cache = SemanticCache.builder()
        .embedder(embeddingClient)          // required: any core EmbeddingClient
        .threshold(0.85)                    // default 0.85, range [0,1]
        .maxEntries(1000)                   // in-memory index capacity, default 1000, LRU evict
        .defaultTtlMillis(10 * 60 * 1000)  // default TTL 10 minutes
        .model("text-embedding-v1")         // optional embedding model name
        .build();
```

`.store(CacheStore)` (default in-process `LruCacheStore`) backs the payload — pass `sure-ai-rag`'s
`RedisCacheStore` for shared multi-instance caching. The vector index lives in memory (linear scan,
controlled scale ≲ thousands); the `ChatResponse` payload goes through the pluggable `CacheStore`.

## End-to-end

```java
record Product(String name, int stock) {}

@AiService(model = "gpt-4o-mini")
interface Catalog {
    @UserMessage("{q}")
    Product lookup(String q);

    @Tool(description = "Look up stock")
    default int stockOf(String item) { return queryStockDb(item); }
}

SemanticCache cache = SemanticCache.builder().embedder(embeddingClient).threshold(0.85).build();

List<Advisor> advisors = List.of(
        new SemanticCacheAdvisor(cache),
        new LoggingAdvisor(),
        new ToolCallingAdvisor(),
        new StructuredOutputValidationAdvisor());

Catalog ai = FrameworkUtil.builder(Catalog.class, client).advisors(advisors).build();

Product p = ai.lookup("Check stock of 'Computer Systems: A Programmer's Perspective'");
```

The model may invoke `stockOf`, self-correct on a bad structured output, and cached questions skip
the model call entirely.

## How it relates

- **AiClient** — framework is platform-agnostic; the terminal is whatever `AiClient` you pass.
- **RAG / Agent** — heavier pipelines live in `sure-ai-rag` / `sure-ai-agent`; the framework's
  `ToolCallingAdvisor` is a lightweight single-interface tool loop. Call a RAG retriever inside a
  `@Tool` method to combine them.
- **Caches** — core exact cache (`../cache.md`) and this semantic cache are complementary;
  `SemanticCache.store(...)` reuses the `CacheStore` SPI.
- **Structured output** — built on core's `responseFormat` (`../structured-output.md`).

## See also

- Copy-paste recipe: [../COOKBOOK.md](../COOKBOOK.md) scenario 17
- Exact cache vs Redis: [../cache.md](../cache.md)
- Full Chinese reference: [../framework.md](../framework.md)
