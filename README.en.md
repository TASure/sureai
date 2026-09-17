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
- **Image Generation**: unified `ImageClient` abstraction supporting DALL·E / Wanx / CogView / ERNIE-ViLG / Gemini Imagen; async platforms handle polling internally, exposing a synchronous API
- **Video Generation**: unified `VideoClient` abstraction supporting Sora / Wan / CogVideoX / Seedance / Azure Sora 2; all platforms use async task polling internally, exposing a synchronous API
- **Speech TTS/STT**: unified `AudioClient` abstraction (TTS synthesis + STT transcription) supporting OpenAI / CosyVoice / GLM-TTS / Doubao / Baidu / Azure Speech; binary audio / URL / Base64 response formats
- **RAG**: end-to-end retrieval-augmented generation pipeline (`sure-ai-rag`)
- **Rerank**: unified `RerankClient` abstraction, Qwen qwen3-rerank integration, pluggable two-stage re-ranking in the RAG retrieval chain
- **Structured Output**: unified `response_format` abstraction (json_object / JSON Schema), zero-dependency `JsonMapper` strong-typed record deserialization, adapted across 8 platforms
- **Multimodal Image Understanding**: `MessagePart` content-block architecture (text + image), normalized image input across OpenAI-compatible / Gemini / Anthropic / Baidu
- **PDF Document Input**: `DocumentPart` content block, PDF document understanding adapted across 5 platforms
- **Prompt Caching**: Anthropic `cache_control` / Gemini `cachedContent` / OpenAI automatic caching, cutting repeated-prefix cost for long contexts
- **Batches**: unified `BatchClient` abstraction, async batch inference across OpenAI / Azure / Zhipu / Anthropic, built-in polling
- **Environment variable auto-config**: lazy-loads from `SURE_AI_*` env vars when not explicitly initialized
- **JDK 21**: records, pattern matching, switch patterns

## Modules & Platforms

| Platform | artifactId | Default baseUrl | Auth | Streaming | Embedding | Image Gen | Video Gen | TTS | STT | Function Calling | Rerank | Structured | Multimodal | PDF | Cache | Batches |
|----------|-----------|-----------------|------|-----------|-----------|-----------|-----------|-----|-----|-----------------|--------|-----------|------------|-----|-------|---------|
| OpenAI | `sure-ai-openai` | `https://api.openai.com/v1` | Bearer | ✅ | ✅ | ✅ DALL·E 3 | ✅ Sora 2 | ✅ tts-1 | ✅ whisper-1 | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ auto | ✅ |
| Azure OpenAI | `sure-ai-azure` | `https://{resource}.openai.azure.com` | api-key header | ✅ | ✅ | ✅ DALL·E 3 | ✅ Sora 2 | ✅ Speech | ✅ Speech | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ auto | ✅ |
| Anthropic | `sure-ai-anthropic` | `https://api.anthropic.com/v1` | x-api-key header | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ tool_use | ✅ | ✅ | ✅ cache_control | ✅ |
| Google Gemini | `sure-ai-gemini` | `https://generativelanguage.googleapis.com/v1beta` | ?key= query param | ✅ | ✅ | ✅ Imagen | ❌ Veo(OAuth) | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ cachedContent | ❌ |
| DeepSeek | `sure-ai-deepseek` | `https://api.deepseek.com` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Qwen | `sure-ai-qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Bearer | ✅ | ✅ | ✅ Wanx (async) | ✅ Wan 2.6 (async) | ✅ CosyVoice | ✅ Qwen-ASR | ✅ | ✅ qwen3-rerank | ✅ | ✅ | ✅ | ❌ | ❌ |
| Zhipu GLM | `sure-ai-zhipu` | `https://open.bigmodel.cn/api/paas/v4` | JWT (HS256) | ✅ | ✅ | ✅ CogView | ✅ CogVideoX (async) | ✅ GLM-TTS | ✅ GLM-ASR | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ |
| Moonshot | `sure-ai-moonshot` | `https://api.moonshot.cn/v1` | Bearer | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Doubao | `sure-ai-doubao` | `https://ark.cn-beijing.volces.com/api/v3` | Bearer | ✅ | ✅ | ❌ | ✅ Seedance (async) | ✅ seed-tts-2.0 | ✅ BigASR | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Baidu Qianfan | `sure-ai-baidu` | `https://aip.baidubce.com` | access_token (auto-cached) | ✅ | ✅ | ✅ ERNIE-ViLG (async) | ❌ | ✅ DuXiaomei | ✅ Short ASR | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Ollama | `sure-ai-ollama` | `http://localhost:11434` | None (local) | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

Aggregation modules: `sure-ai-all` (one dependency for all platforms), `sure-ai-bom` (version management).

## Quick Start

```java
import com.sure.ai.openai.OpenAiUtil;

// 1. Set env var SURE_AI_OPENAI_API_KEY
// 2. One-line call
String reply = OpenAiUtil.chat("gpt-4o-mini", "Hello!").firstText();
System.out.println(reply);
```

### Image Generation

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.ImageRequest;

// Synchronous return (async platforms like Wanx/ERNIE-ViLG poll internally)
String imageUrl = OpenAiUtil.image(OpenAiModels.DALL_E_3, "a cute kitten").firstUrl();

// Or use Builder for size/quality/count
ImageRequest req = ImageRequest.builder()
    .model(OpenAiModels.DALL_E_3)
    .prompt("cyberpunk city skyline at night")
    .size("1024x1024")
    .quality("hd")
    .build();
String url = OpenAiUtil.image(req).firstUrl();
```

See [docs/images.md](docs/images.md) for platform configuration and async polling details.

### Video Generation

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.VideoRequest;

// All platforms are async; SDK polls internally, returns synchronously
String videoUrl = OpenAiUtil.video(OpenAiModels.SORA_2, "a cat running on grass").firstUrl();

// Or use Builder for duration/resolution/first-last-frame
VideoRequest req = VideoRequest.builder()
    .model(OpenAiModels.SORA_2)
    .prompt("cyberpunk city skyline at night, slow motion")
    .duration(8)
    .size("1280x720")
    .build();
String url = OpenAiUtil.video(req).firstUrl();
```

See [docs/video.md](docs/video.md) for platform configuration and async polling details.

### Speech TTS / STT

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.SttResponse;

// TTS: text → binary audio
TtsResponse tts = OpenAiUtil.tts(OpenAiModels.TTS_1, "Hello, world", "alloy");
byte[] audio = tts.audio();  // save as mp3 directly

// STT: audio → transcribed text
SttResponse stt = OpenAiUtil.stt(OpenAiModels.WHISPER_1, audio);
System.out.println(stt.text());
```

See [docs/audio.md](docs/audio.md) for platform configuration and response format details.

### Structured Output

```java
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.util.JsonMapper;

// Target record: field names must match the JSON keys the model emits
record CityInfo(String city, String country, int population) {}

String text = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("Extract city info as JSON only: city/country/population. Text: Tokyo is the capital of Japan.")))
    .responseFormat("json_object")
    .build())
    .firstText();

// Zero-dependency strong-typed deserialization, no manual string parsing
CityInfo info = JsonMapper.fromJson(Json.parse(text).getAsJsonObject(), CityInfo.class);
```

Per-platform `response_format` differences (Anthropic simulated via forced tool_use,
Gemini `responseSchema`, Baidu string values) see [docs/structured-output.md](docs/structured-output.md).

### Multimodal Image Understanding

```java
import com.sure.ai.model.*;

// One user message = text part + image part (URL or Base64)
String desc = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o")
    .messages(List.of(ChatMessage.user(List.of(
        TextPart.of("Describe this image in one sentence."),
        ImagePart.ofUrl("https://example.com/cat.jpg")
    ))))
    .build())
    .firstText();
```

Content-block architecture, PDF input and prompt caching see [docs/multimodal.md](docs/multimodal.md);
Rerank see [docs/rerank.md](docs/rerank.md); Batches see [docs/batches.md](docs/batches.md).

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
