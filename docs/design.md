# sureai 架构设计

## 设计目标

1. **零第三方依赖**：运行期仅依赖 `sure-core:0.2.0`，HTTP 用 JDK21 `HttpClient`，JSON 自研。
2. **模块级隔离**：每个平台一个独立 Maven 模块，平台间零相互依赖，引入单个平台不会传递拉入其他平台。
3. **静态工具类开箱即用**：每个平台提供 `<Pla>Util` 静态入口，`init(apiKey)` 后直接 `chat(model, prompt)`。
4. **协议复用**：OpenAI 兼容平台复用 `OpenAiCompatClient`，非兼容平台（Anthropic / Gemini / Baidu / Ollama）在 core 抽象之上自行实现协议映射。

## 架构图

```mermaid
graph TB
    subgraph 使用者代码
        APP[业务代码]
    end

    subgraph 平台模块（互相隔离）
        OU[OpenAiUtil]
        AU[AzureUtil]
        ANU[AnthropicUtil]
        GU[GeminiUtil]
        DU[DeepSeekUtil]
        QU[QwenUtil]
        ZU[ZhipuUtil]
        MU[MoonshotUtil]
        DOU[DoubaoUtil]
        BU[BaiduUtil]
        OU2[OllamaUtil]
    end

    subgraph sure-ai-core
        OC[OpenAiCompatClient]
        AC[AbstractAiClient]
        AIC[AiClient 接口]
        EC[EmbeddingClient 接口]
        MODEL[model 包<br/>ChatRequest/ChatResponse/...]
        EXC[exception 包]
        JSON[internal.json<br/>自研 JSON]
        SSE[internal.http<br/>SSE 解析]
    end

    APP --> OU & AU & ANU & GU & DU & QU & ZU & MU & DOU & BU & OU2

    OU --> OC
    DU --> OC
    QU --> OC
    ZU --> OC
    MU --> OC
    DOU --> OC
    AU --> AC
    ANU --> AC
    GU --> AC
    BU --> AC
    OU2 --> AC

    OC --> AC
    AC --> AIC
    AC --> EC
    AC --> MODEL
    AC --> EXC
    AC --> JSON
    AC --> SSE
```

## 核心抽象

### AiClient 接口

```java
public interface AiClient {
    String name();
    ChatResponse chat(ChatRequest request);
    void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer);
    void close();
    default ChatResponse chat(String model, String prompt) { ... }
}
```

### AbstractAiClient

- 管理 `HttpClient` 生命周期（connectTimeout / proxy）。
- `doPost(path, body)`：JSON POST + 鉴权钩子 + 错误映射 + 429/5xx 重试。
- `doPostStream(path, body, consumer)`：SSE 流式 POST。
- `applyAuth(builder, config)`：abstract，子类实现鉴权头。
- `mapError(status, body)`：可覆盖，默认 401/403→Auth、429→RateLimit、其他→Api。

### OpenAiCompatClient

- 完整实现 OpenAI Chat Completions / Embeddings 协议。
- `chatPath` / `embeddingsPath` 为 protected 字段，子类可覆盖。
- 支持 tools/function calling、image_url 多模态、usage 解析、SSE 流式。

## 平台分类

| 类型 | 平台 | 实现方式 |
|------|------|----------|
| 原生 OpenAI | OpenAI | 直接用 OpenAiCompatClient |
| OpenAI 兼容 | DeepSeek, Qwen, Moonshot, Doubao, Zhipu | 继承 OpenAiCompatClient，覆盖 baseUrl / applyAuth |
| 半兼容 | Azure | 继承 AbstractAiClient，URL 含 deployment + api-version，api-key 头 |
| 非兼容 | Anthropic | 继承 AbstractAiClient，Messages API + SSE 事件映射 |
| 非兼容 | Gemini | 继承 AbstractAiClient，generateContent + key 查询参数 |
| 非兼容 | Baidu | 继承 AbstractAiClient，OAuth token + 文心接口 |
| 非兼容 | Ollama | 继承 AbstractAiClient，ndjson 流式 |

## 自研 JSON 组件

位于 `com.sure.ai.internal.json`，包名 `internal` 声明不对外承诺稳定 API，但跨模块共享。

- `JsonElement`：抽象基类，子类 `JsonPrimitive` / `JsonObject` / `JsonArray`。
- `JsonObject extends LinkedHashMap<String, JsonElement>`：保序，提供类型安全 getter。
- `JsonArray extends ArrayList<JsonElement>`。
- `JsonParser`：递归下降解析，支持转义、\uXXXX、大数、科学计数法。
- `JsonWriter`：控制字符转义，UTF-8 直出中文。
- `Json` 门面：`parse()` / `stringify()` / `object()` / `array()`。

## 错误体系

```
AiException (RuntimeException)
├── AiApiException (httpStatus, errorCode, rawBody)
│   ├── AiAuthException (401/403)
│   └── AiRateLimitException (429, retryAfterSeconds)
└── AiTimeoutException
```

## 重试策略

- 仅对 429 和 5xx 重试，4xx 不重试。
- 429 优先读 `Retry-After` 头（秒），否则指数退避（1s → 2s → 4s）。
- 最大重试次数由 `AiConfig.maxRetries` 控制（默认 2）。
