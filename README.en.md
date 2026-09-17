# sureai

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21+-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven Central](https://img.shields.io/badge/maven--central-0.1.0-lightgrey.svg)](https://central.sonatype.com/)
[![CI](https://img.shields.io/badge/CI-passing-brightgreen.svg)](.github/workflows/ci.yml)

**Zero third-party dependency Java LLM integration toolkit.** One independent module and static utility class per mainstream AI platform, with strict module-level isolation — pull in only what you need.

## How it differs from langchain4j / Spring AI

| Dimension | sureai | langchain4j | Spring AI |
|-----------|--------|-------------|-----------|
| Third-party deps | **Zero** (JDK + built-in JSON/HTTP only) | Heavy transitive chain | Tied to Spring ecosystem |
| Zero-config usage | **Static utility, one-line call** | Requires Builder wiring | Requires @Configuration + Beans |
| Chinese platform coverage | **All 11 platforms** (Baidu, Zhipu, Doubao, etc.) | Partial | Partial |
| Module isolation | **Per-module zero-dep**, pull only what you need | Monolithic | Monolithic |
| JDK requirement | 21+ (records / pattern matching) | 17+ | 17+ |

## Features

- **Zero third-party runtime dependencies**: built-in lightweight JSON parser and HTTP client, no OkHttp/Jackson/Netty
- **Static utilities out of the box**: `OpenAiUtil.chat(model, prompt)` — one line to chat
- **Module-level isolation**: pull only the platform modules you need
- **Full Chinese platform coverage**: OpenAI / Azure / Anthropic / Gemini / DeepSeek / Qwen / Zhipu / Moonshot / Doubao / Baidu / Ollama
- **Streaming**: unified SSE streaming interface with per-chunk callback
- **Function Calling**: tool declaration and invocation closed loop
- **Embedding**: vector generation (see table below for supported platforms)
- **RAG**: end-to-end retrieval-augmented generation pipeline (`sure-ai-rag`)
- **Environment variable auto-config**: lazy-loads from `SURE_AI_*` env vars when not explicitly initialized
- **JDK 21**: records, pattern matching, switch patterns

## Modules & Platforms

| Platform | artifactId | Default baseUrl | Auth | Streaming | Embedding | Function Calling |
|----------|-----------|-----------------|------|-----------|-----------|-----------------|
| OpenAI | `sure-ai-openai` | `https://api.openai.com/v1` | Bearer | ✅ | ✅ | ✅ |
| Azure OpenAI | `sure-ai-azure` | `https://{resource}.openai.azure.com` | api-key header | ✅ | ✅ | ✅ |
| Anthropic | `sure-ai-anthropic` | `https://api.anthropic.com/v1` | x-api-key header | ✅ | ❌ | ✅ |
| Google Gemini | `sure-ai-gemini` | `https://generativelanguage.googleapis.com/v1beta` | ?key= query param | ✅ | ✅ | ✅ |
| DeepSeek | `sure-ai-deepseek` | `https://api.deepseek.com` | Bearer | ✅ | ❌ | ✅ |
| Qwen | `sure-ai-qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Bearer | ✅ | ✅ | ✅ |
| Zhipu GLM | `sure-ai-zhipu` | `https://open.bigmodel.cn/api/paas/v4` | JWT (HS256) | ✅ | ✅ | ✅ |
| Moonshot | `sure-ai-moonshot` | `https://api.moonshot.cn/v1` | Bearer | ✅ | ✅ | ✅ |
| Doubao | `sure-ai-doubao` | `https://ark.cn-beijing.volces.com/api/v3` | Bearer | ✅ | ✅ | ✅ |
| Baidu Qianfan | `sure-ai-baidu` | `https://aip.baidubce.com` | access_token (auto-cached) | ✅ | ✅ | ✅ |
| Ollama | `sure-ai-ollama` | `http://localhost:11434` | None (local) | ✅ | ✅ | ✅ |

Aggregation modules: `sure-ai-all` (one dependency for all platforms), `sure-ai-bom` (version management).

## Quick Start

```java
import com.sure.ai.openai.OpenAiUtil;

// 1. Set env var SURE_AI_OPENAI_API_KEY
// 2. One-line call
String reply = OpenAiUtil.chat("gpt-4o-mini", "Hello!").firstText();
System.out.println(reply);
```

## Maven Dependencies

Pull individual platform modules:

```xml
<!-- OpenAI -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Azure OpenAI -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-azure</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Anthropic Claude -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-anthropic</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Google Gemini -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-gemini</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- DeepSeek -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-deepseek</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Qwen DashScope -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-qwen</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Zhipu GLM -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-zhipu</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Moonshot Kimi -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-moonshot</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Doubao Volcano Engine -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-doubao</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Baidu Qianfan -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-baidu</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Ollama Local -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-ollama</artifactId>
    <version>0.1.0</version>
</dependency>
```

Pull all platforms at once:

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-all</artifactId>
    <version>0.1.0</version>
    <type>pom</type>
</dependency>
```

## Streaming

```java
OpenAiUtil.chatStream(
    ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("Introduce Java in one sentence.")))
        .build(),
    chunk -> {
        if (chunk.deltaText() != null) {
            System.out.print(chunk.deltaText());
        }
    }
);
```

## Function Calling

```java
ToolFunction weatherFn = ToolFunction.of(
    "getWeather",
    "Get current weather for a city",
    "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
);

ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("How's the weather in Shanghai?")))
    .tools(List.of(ToolSpec.of(weatherFn)))
    .build();

ChatResponse resp = OpenAiUtil.chat(req);
List<ToolCall> calls = resp.choices().get(0).message().toolCalls();
// Execute the tool, then append result as ChatMessage.tool(toolCallId, result) and re-request
```

## Embedding

```java
EmbeddingResponse resp = OpenAiUtil.embed("text-embedding-3-small", "hello world");
float[] vector = resp.embeddings().get(0);
System.out.println("Vector dimensions: " + vector.length);
```

## RAG (Retrieval-Augmented Generation)

`sure-ai-rag` provides an end-to-end RAG pipeline (text splitting → embedding → similarity
retrieval → augmented generation), decoupled from any platform. Combine any platform client
that supports chat + embeddings:

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-rag</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
OpenAiClient client = OpenAiClient.builder().apiKey("sk-xxx").build();

RagPipeline pipeline = RagUtil.pipeline(client, client,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);

pipeline.ingest("sureai-intro", "sureai is a zero-dependency Java LLM toolkit ...");
ChatResponse answer = pipeline.ask("What capabilities does sureai support?");
```

An in-memory vector store (cosine similarity) is included; implement the `VectorStore`
interface to plug in Milvus / FAISS / pgvector. See [docs/rag.md](docs/rag.md).

## Environment Variables

| Platform | Variable | Required | Description |
|----------|----------|----------|-------------|
| OpenAI | `SURE_AI_OPENAI_API_KEY` | ✅ | API Key |
| | `SURE_AI_OPENAI_BASE_URL` | ❌ | Override default baseUrl |
| Azure | `SURE_AI_AZURE_API_KEY` | ✅ | API Key |
| | `SURE_AI_AZURE_RESOURCE` | ❌ | Azure resource name |
| | `SURE_AI_AZURE_BASE_URL` | ❌ | Full baseUrl |
| Anthropic | `SURE_AI_ANTHROPIC_API_KEY` | ✅ | API Key |
| | `SURE_AI_ANTHROPIC_BASE_URL` | ❌ | Override default baseUrl |
| Gemini | `SURE_AI_GEMINI_API_KEY` | ✅ | API Key |
| | `SURE_AI_GEMINI_BASE_URL` | ❌ | Override default baseUrl |
| DeepSeek | `SURE_AI_DEEPSEEK_API_KEY` | ✅ | API Key |
| | `SURE_AI_DEEPSEEK_BASE_URL` | ❌ | Override default baseUrl |
| Qwen | `SURE_AI_QWEN_API_KEY` | ✅ | DashScope API Key |
| | `SURE_AI_QWEN_BASE_URL` | ❌ | Override default baseUrl |
| Zhipu | `SURE_AI_ZHIPU_API_KEY` | ✅ | `id.secret` format |
| | `SURE_AI_ZHIPU_BASE_URL` | ❌ | Override default baseUrl |
| Moonshot | `SURE_AI_MOONSHOT_API_KEY` | ✅ | API Key |
| | `SURE_AI_MOONSHOT_BASE_URL` | ❌ | Override default baseUrl |
| Doubao | `SURE_AI_DOUBAO_API_KEY` | ✅ | Ark API Key |
| | `SURE_AI_DOUBAO_BASE_URL` | ❌ | Override default baseUrl |
| Baidu | `SURE_AI_BAIDU_API_KEY` | ✅ | Qianfan API Key |
| | `SURE_AI_BAIDU_SECRET_KEY` | ✅ | Qianfan Secret Key |
| | `SURE_AI_BAIDU_BASE_URL` | ❌ | Override default baseUrl |
| Ollama | `SURE_AI_OLLAMA_BASE_URL` | ❌ | Override `localhost:11434` |

## Architecture & Isolation

sureai enforces strict module-level isolation:

```
sure-ai-core          ← common models/interfaces/HTTP/JSON (depended on by all platforms)
sure-ai-rag           ← RAG pipeline (depends on core; platform-agnostic)
  ├── sure-ai-openai
  ├── sure-ai-azure
  ├── sure-ai-anthropic
  ├── sure-ai-gemini
  ├── sure-ai-deepseek
  ├── sure-ai-qwen
  ├── sure-ai-zhipu
  ├── sure-ai-moonshot
  ├── sure-ai-doubao
  ├── sure-ai-baidu
  └── sure-ai-ollama
sure-ai-bom           ← version BOM
sure-ai-all           ← aggregate all platforms + RAG
sure-ai-examples      ← usage examples
```

**Core design principles:**

- Each platform module and the RAG module depend only on `sure-ai-core`; **zero cross-module dependencies**
- Core module ships with a built-in JSON parser and SSE line reader — no third-party libraries
- Static utilities use double-checked locking lazy init; auto-loads from env vars when not explicitly initialized
- Platform-specific auth logic (JWT / access_token cache / custom headers) is encapsulated within each module
- The RAG pipeline is platform-agnostic: any platform's chat + embedding clients can be combined

## Build

```bash
# Requires JDK 21+ and Maven 3.9+
mvn -B clean verify
```

This runs: compile → unit tests → checkstyle → spotbugs → jacoco coverage gate → license header check.

## Contributing

Issues and PRs are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE) © sureai contributors
