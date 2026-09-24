# sureai

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21+-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven Central](https://img.shields.io/badge/maven--central-0.1.0-lightgrey.svg)](https://central.sonatype.com/)
[![CI](https://img.shields.io/badge/CI-passing-brightgreen.svg)](.github/workflows/ci.yml)

**零第三方依赖的 Java 大模型接入工具基础设施。** 每个主流 AI 平台一个独立模块与静态入口工具类，模块间互相隔离，按需引入。

## 与同类框架的差异化

| 维度 | sureai | langchain4j | Spring AI |
|------|--------|-------------|-----------|
| 第三方依赖 | **零**（仅 JDK + 自研 JSON/HTTP） | 传递依赖链庞大 | 绑定 Spring 生态 |
| 开箱即用 | **静态工具类一行调用** | 需 Builder 装配 | 需 @Configuration + Bean |
| 国产平台覆盖 | **11 个平台全覆盖**（含百度、智谱、豆包等） | 部分覆盖 | 部分覆盖 |
| 模块隔离 | **模块级零依赖**，只引入需要的平台 | 整体引入 | 整体引入 |
| JDK 要求 | 21+（record/pattern matching） | 17+ | 17+ |

## 特性

- **零第三方运行期依赖**：内置轻量 JSON 解析与 HTTP 客户端，不引入 OkHttp/Jackson/Netty
- **静态工具类开箱即用**：`OpenAiUtil.chat(model, prompt)` 一行完成对话
- **模块级隔离**：只引入需要的平台模块，不引入无关依赖
- **国产平台全覆盖**：OpenAI / Azure / Anthropic / Gemini / DeepSeek / 通义千问 / 智谱 / Moonshot / 豆包 / 百度千帆 / Ollama
- **流式调用**：统一 SSE 流式接口，逐片回调
- **Function Calling**：工具声明与调用闭环
- **Embedding**：向量生成（支持平台见下表）
- **图像生成**：`ImageClient` 统一抽象，支持 DALL·E / 通义万相 / CogView / 文心一格 / Gemini Imagen，异步平台内部轮询屏蔽，对外同步返回
- **视频生成**：`VideoClient` 统一抽象，支持 Sora / 通义万相 Wan / CogVideoX / Seedance / Azure Sora 2，全平台异步任务轮询屏蔽，对外同步返回
- **语音 TTS/STT**：`AudioClient` 统一抽象（TTS 合成 + STT 转录），支持 OpenAI / CosyVoice / GLM-TTS / 豆包 / 百度 / Azure Speech，二进制音频 / URL / Base64 三种响应形态
- **RAG 检索增强问答**：`sure-ai-rag` 端到端管线（文档加载器：本地文件/URL；分块：递归字符/Markdown/固定大小；向量存储；检索：向量 + BM25 关键词混合加权融合；增强生成）
- **Agent 编排（ReAct 多工具循环）**：`sure-ai-agent` 工具注册中心 + Function Calling 参数校验 + ReAct 编排器，任意 `AiClient` 可驱动，异常/超限/超时全防护
- **可观测性（重试回调/指标/限流）**：`RetryListener` 重试事件回调 + `MetricsCollector` 指标埋点（内置零依赖 `AiMetrics`）+ 客户端 QPS 限流（sure-core 令牌桶），`sure-ai-micrometer` 可选 Micrometer/Prometheus 桥接，未挂载零开销。见 [docs/observability.md](docs/observability.md)
- **响应缓存**：`ChatCacheKey` 请求归一化 SHA-256 + `CacheStore` SPI + 内置 `LruCacheStore`（LRU+TTL，纯 JDK），默认关闭零开销，命中不触发网络/指标/重试，Redis 适配见文档示例。见 [docs/cache.md](docs/cache.md)
- **Spring Boot Starter**：`sure-ai-spring-boot-starter` 自动配置（`sure.ai.<platform>.api-key` 等属性绑定 + `@ConditionalOnProperty` 条件装配 + `@Autowired` 注入），仅 Spring Boot 工程使用，core/平台模块零 Spring 依赖。见 [docs/spring-boot.md](docs/spring-boot.md)
- **Rerank 重排序**：`RerankClient` 统一抽象，通义千问 qwen3-rerank 接入，二阶段精排可无缝接入 RAG 检索链路
- **结构化输出**：`response_format` 统一抽象（json_object / JSON Schema），`JsonMapper` 零依赖强类型 record 反序列化，8 平台适配
- **多模态图像理解**：`MessagePart` 内容块架构（文本 + 图片），OpenAI 兼容 / Gemini / Anthropic / 百度 图片输入归一
- **PDF 文档输入**：`DocumentPart` 内容块，5 平台 PDF 文档理解适配
- **Prompt 缓存**：Anthropic `cache_control` / Gemini `cachedContent` / OpenAI 自动缓存，降低长上下文重复前缀成本
- **Batches 批处理**：`BatchClient` 统一抽象，OpenAI / Azure / 智谱 / Anthropic 异步批量推理，内置轮询
- **Realtime 实时语音**：`RealtimeClient` 全双工 WebSocket 抽象，OpenAI / Gemini / 通义千问 / 智谱 / 豆包 5 平台接入，连接器可注入便于 mock
- **思考模式**：`reasoningEffort` / `thinkingConfig` 统一抽象 + 思维链 `reasoningContent` 解析，OpenAI / Azure / Gemini / Anthropic / 通义千问 5 平台适配
- **Grounding 联网**：`grounding` 统一开关，工具式（web_search / googleSearch）与布尔式（enable_search）双范式，OpenAI / Azure / Gemini / 通义千问 / 智谱 / 豆包 6 平台接入，引用来源解析
- **微调**：`FineTuneClient` 统一抽象（上传训练文件 + 创建/查询任务），OpenAI / Azure / 百度千帆 3 平台接入
- **内容审核**：`ModerationClient` 统一抽象，类别与分数归一，OpenAI / Azure 接入
- **模型列表**：`ModelsClient` 统一抽象，OpenAI / Azure / Gemini / Anthropic / 通义千问 5 平台接入
- **环境变量自动配置**：未显式 init 时自动从 `SURE_AI_*` 环境变量读取
- **JDK 21**：record / pattern matching / switch 模式

## 模块与平台一览

| 平台 | artifactId | 默认 baseUrl | 鉴权方式 | 流式 | Embedding | 图像生成 | 视频生成 | TTS | STT | Function Calling | Rerank | 结构化输出 | 多模态 | PDF | 缓存 | Batches | Realtime | 思考 | Grounding | 微调 | 审核 | 模型列表 |
|------|-----------|-------------|---------|------|-----------|---------|---------|-----|-----|-----------------|--------|-----------|--------|-----|------|---------|---------|------|-----------|------|------|---------|
| OpenAI | `sure-ai-openai` | `https://api.openai.com/v1` | Bearer | ✅ | ✅ | ✅ DALL·E 3 | ✅ Sora 2 | ✅ tts-1 | ✅ whisper-1 | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ 自动 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Azure OpenAI | `sure-ai-azure` | `https://{resource}.openai.azure.com` | api-key 头 | ✅ | ✅ | ✅ DALL·E 3 | ✅ Sora 2 | ✅ Speech | ✅ Speech | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ 自动 | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Anthropic | `sure-ai-anthropic` | `https://api.anthropic.com/v1` | x-api-key 头 | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ tool_use | ✅ | ✅ | ✅ cache_control | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |
| Google Gemini | `sure-ai-gemini` | `https://generativelanguage.googleapis.com/v1beta` | ?key= 查询参数 | ✅ | ✅ | ✅ Imagen | ❌ Veo(OAuth) | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ cachedContent | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| DeepSeek | `sure-ai-deepseek` | `https://api.deepseek.com` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 通义千问 | `sure-ai-qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Bearer | ✅ | ✅ | ✅ 通义万相（异步） | ✅ Wan 2.6（异步） | ✅ CosyVoice | ✅ Qwen-ASR | ✅ | ✅ qwen3-rerank | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ 控制台 | ❌ | ✅ |
| 智谱 GLM | `sure-ai-zhipu` | `https://open.bigmodel.cn/api/paas/v4` | JWT (HS256) | ✅ | ✅ | ✅ CogView | ✅ CogVideoX（异步） | ✅ GLM-TTS | ✅ GLM-ASR | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ 控制台 | ❌ | ❌ |
| Moonshot | `sure-ai-moonshot` | `https://api.moonshot.cn/v1` | Bearer | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 豆包 | `sure-ai-doubao` | `https://ark.cn-beijing.volces.com/api/v3` | Bearer | ✅ | ✅ | ❌ | ✅ Seedance（异步） | ✅ seed-tts-2.0 | ✅ 录音文件识别 | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ 控制台 | ❌ | ❌ |
| 百度千帆 | `sure-ai-baidu` | `https://aip.baidubce.com` | access_token（自动缓存） | ✅ | ✅ | ✅ 文心一格（异步） | ❌ | ✅ 度小美 | ✅ 短语音识别 | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| Ollama | `sure-ai-ollama` | `http://localhost:11434` | 无（本地服务） | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

聚合模块：`sure-ai-all`（一个依赖引入全部平台）、`sure-ai-bom`（版本统一管理）。

## 快速开始

```java
import com.sure.ai.openai.OpenAiUtil;

// 1. 设置环境变量 SURE_AI_OPENAI_API_KEY
// 2. 一行调用
String reply = OpenAiUtil.chat("gpt-4o-mini", "你好！").firstText();
System.out.println(reply);
```

### 图像生成

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.ImageRequest;

// 同步返回（异步平台如通义万相/文心一格内部自动轮询）
String imageUrl = OpenAiUtil.image(OpenAiModels.DALL_E_3, "一只可爱的小猫咪").firstUrl();

// 或使用 Builder 配置尺寸/质量/数量
ImageRequest req = ImageRequest.builder()
    .model(OpenAiModels.DALL_E_3)
    .prompt("赛博朋克风格的城市夜景")
    .size("1024x1024")
    .quality("hd")
    .build();
String url = OpenAiUtil.image(req).firstUrl();
```

更多平台配置与异步轮询说明见 [docs/images.md](docs/images.md)。

### 视频生成

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.VideoRequest;

// 全平台异步任务，SDK 内部轮询，对外同步返回
String videoUrl = OpenAiUtil.video(OpenAiModels.SORA_2, "一只猫咪在草地上奔跑").firstUrl();

// 或使用 Builder 配置时长/分辨率/首尾帧
VideoRequest req = VideoRequest.builder()
    .model(OpenAiModels.SORA_2)
    .prompt("赛博朋克风格的城市夜景，慢镜头")
    .duration(8)
    .size("1280x720")
    .build();
String url = OpenAiUtil.video(req).firstUrl();
```

更多平台配置与异步轮询说明见 [docs/video.md](docs/video.md)。

### 语音 TTS / STT

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.SttResponse;

// TTS：文本 → 二进制音频
TtsResponse tts = OpenAiUtil.tts(OpenAiModels.TTS_1, "你好，世界", "alloy");
byte[] audio = tts.audio();  // 可直接保存为 mp3

// STT：音频 → 转写文本
SttResponse stt = OpenAiUtil.stt(OpenAiModels.WHISPER_1, audio);
System.out.println(stt.text());
```

更多平台配置与响应形态说明见 [docs/audio.md](docs/audio.md)。

### 结构化输出

```java
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.util.JsonMapper;

// 目标 record：字段名与模型输出 JSON 的 key 一致
record CityInfo(String city, String country, int population) {}

String text = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("提取城市信息，只输出 JSON：city/country/population。原文：东京是日本首都。")))
    .responseFormat("json_object")
    .build())
    .firstText();

// 零依赖强类型反序列化，避免手写字符串解析
CityInfo info = JsonMapper.fromJson(Json.parse(text).getAsJsonObject(), CityInfo.class);
```

各平台 response_format 差异（Anthropic 自动 tool_use 模拟、Gemini responseSchema、百度字符串取值）见
[docs/structured-output.md](docs/structured-output.md)。

### 多模态图像理解

```java
import com.sure.ai.model.*;

// 一条用户消息 = 文本片段 + 图片片段（URL 或 Base64）
String desc = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o")
    .messages(List.of(ChatMessage.user(List.of(
        TextPart.of("请用一句话描述这张图片。"),
        ImagePart.ofUrl("https://example.com/cat.jpg")
    ))))
    .build())
    .firstText();
```

内容块架构、PDF 文档输入与 Prompt 缓存见 [docs/multimodal.md](docs/multimodal.md)；
Rerank 见 [docs/rerank.md](docs/rerank.md)；Batches 见 [docs/batches.md](docs/batches.md)。

### 内容审核

```java
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.openai.OpenAiUtil;

ModerationResponse resp = OpenAiUtil.client()
    .moderate(ModerationRequest.of("待审核文本"));

System.out.println("flagged=" + resp.flagged());   // 任一结果命中即 true
resp.results().forEach(r -> r.categoryScores()
    .forEach((k, v) -> System.out.printf("%s=%.4f%n", k, v)));
```

详见 [docs/moderation.md](docs/moderation.md)。

### 模型列表

```java
import com.sure.ai.model.Model;
import com.sure.ai.openai.OpenAiUtil;

for (Model m : OpenAiUtil.client().listModels()) {
    System.out.println(m.id() + "  owned_by=" + m.ownedBy());
}
```

详见 [docs/models.md](docs/models.md)；Realtime 见 [docs/realtime.md](docs/realtime.md)，
思考模式见 [docs/thinking.md](docs/thinking.md)，Grounding 见 [docs/grounding.md](docs/grounding.md)，
微调见 [docs/fine-tuning.md](docs/fine-tuning.md)。

## Maven 依赖

按需引入单个平台模块：

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

<!-- 通义千问 DashScope -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-qwen</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- 智谱 GLM -->
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

<!-- 火山引擎豆包 -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-doubao</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- 百度千帆 -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-baidu</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Ollama 本地模型 -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-ollama</artifactId>
    <version>0.1.0</version>
</dependency>
```

引入全部平台：

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-all</artifactId>
    <version>0.1.0</version>
    <type>pom</type>
</dependency>
```

## 流式调用

```java
OpenAiUtil.chatStream(
    ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("用一句话介绍 Java。")))
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
    "查询指定城市的当前天气",
    "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
);

ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("上海今天天气怎么样？")))
    .tools(List.of(ToolSpec.of(weatherFn)))
    .build();

ChatResponse resp = OpenAiUtil.chat(req);
List<ToolCall> calls = resp.choices().get(0).message().toolCalls();
// 执行工具后将结果作为 ChatMessage.tool(toolCallId, result) 追加，再次请求
```

## Embedding

```java
EmbeddingResponse resp = OpenAiUtil.embed("text-embedding-3-small", "测试文本");
float[] vector = resp.embeddings().get(0);
System.out.println("向量维度: " + vector.length);
```

## RAG 检索增强问答

`sure-ai-rag` 提供端到端 RAG 管线（文本分块 → 向量化 → 相似度检索 → 增强生成），
与平台解耦，任意支持对话 + Embedding 的平台客户端可直接组合：

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

pipeline.ingest("sureai-intro", "sureai 是一个零第三方依赖的 Java 大模型接入工具库……");
ChatResponse answer = pipeline.ask("sureai 支持哪些能力？");
```

内置进程内向量库（余弦相似度），亦可实现 `VectorStore` 接口接入
Milvus / FAISS / pgvector 等外部向量库。内置文档加载器（本地文件 / URL）、
BM25 关键词检索与向量+关键词混合检索、Markdown / 固定大小分块器。
详见 [docs/rag.md](docs/rag.md)。

## 环境变量配置

| 平台 | 环境变量 | 必填 | 说明 |
|------|---------|------|------|
| OpenAI | `SURE_AI_OPENAI_API_KEY` | ✅ | API Key |
| | `SURE_AI_OPENAI_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Azure | `SURE_AI_AZURE_API_KEY` | ✅ | API Key |
| | `SURE_AI_AZURE_RESOURCE` | ❌ | Azure 资源名 |
| | `SURE_AI_AZURE_BASE_URL` | ❌ | 完整 baseUrl |
| Anthropic | `SURE_AI_ANTHROPIC_API_KEY` | ✅ | API Key |
| | `SURE_AI_ANTHROPIC_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Gemini | `SURE_AI_GEMINI_API_KEY` | ✅ | API Key |
| | `SURE_AI_GEMINI_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| DeepSeek | `SURE_AI_DEEPSEEK_API_KEY` | ✅ | API Key |
| | `SURE_AI_DEEPSEEK_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 通义千问 | `SURE_AI_QWEN_API_KEY` | ✅ | DashScope API Key |
| | `SURE_AI_QWEN_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 智谱 | `SURE_AI_ZHIPU_API_KEY` | ✅ | `id.secret` 格式 |
| | `SURE_AI_ZHIPU_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Moonshot | `SURE_AI_MOONSHOT_API_KEY` | ✅ | API Key |
| | `SURE_AI_MOONSHOT_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 豆包 | `SURE_AI_DOUBAO_API_KEY` | ✅ | Ark API Key |
| | `SURE_AI_DOUBAO_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 百度千帆 | `SURE_AI_BAIDU_API_KEY` | ✅ | 千帆 API Key |
| | `SURE_AI_BAIDU_SECRET_KEY` | ✅ | 千帆 Secret Key |
| | `SURE_AI_BAIDU_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Ollama | `SURE_AI_OLLAMA_BASE_URL` | ❌ | 覆盖 `localhost:11434` |

## 架构与隔离设计

sureai 采用严格的模块级隔离架构：

```
sure-ai-core          ← 公共模型/接口/HTTP/JSON（所有平台依赖此模块）
sure-ai-rag           ← RAG 检索增强生成（依赖 core，与平台解耦）
sure-ai-agent         ← Agent 编排 ReAct 多工具循环（依赖 core，与平台解耦）
sure-ai-micrometer    ← 可观测性 Micrometer 桥接（依赖 core，micrometer-core provided 不传递）
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
sure-ai-bom           ← 版本统一管理（BOM）
sure-ai-all           ← 聚合引入全部平台、RAG 与 Agent
sure-ai-examples      ← 使用示例
```

**核心设计原则：**

- 每个平台模块与 RAG 模块只依赖 `sure-ai-core`，模块间**零依赖**
- 核心模块内置自研 JSON 解析器与 SSE 行读取器，不引入第三方库
- 静态工具类双检锁懒加载，未初始化时从环境变量自动读取
- 各平台特有鉴权逻辑（JWT / access_token 缓存 / 自定义请求头）封装在各自模块内
- RAG 管线与平台解耦：任意平台的对话 + Embedding 客户端可直接组合

## 构建指南

```bash
# 需要 JDK 21+ 和 Maven 3.9+
mvn -B clean verify
```

此命令执行：编译 → 单元测试 → checkstyle → spotbugs → jacoco 覆盖率门禁 → license 头校验。

## 贡献指南

欢迎提交 Issue 和 PR！详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开发与治理团队

后续迭代由「软件研发小组」负责：小组长统筹需求与验收，产品经理定需求与验收标准，软件架构师做模块与 API 设计，研发工程师实现，全栈代码质检官独立审查（P0/P1/P2 分级）。详见 [docs/TEAM.md](docs/TEAM.md)，机器可读配置见 [`.team/`](.team/)。

## License

[Apache License 2.0](LICENSE) © sureai contributors
