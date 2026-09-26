# Mistral AI 平台

> 模块：`sure-ai-mistral` ｜ Base URL：`https://api.mistral.ai/v1` ｜ 协议：OpenAI 兼容

## 快速开始

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-mistral</artifactId>
    <version>1.4.0</version>
</dependency>
```

```java
MistralClient client = new MistralClient(
    AiConfig.builder().apiKey(System.getenv("SURE_AI_MISTRAL_API_KEY")).build()
);
ChatResponse resp = client.chat(ChatRequest.builder()
    .model(MistralModels.MISTRAL_LARGE_LATEST)
    .messages(List.of(ChatMessage.user("你好")))
    .build());
```

便捷入口：

```java
ChatResponse resp = MistralUtil.chat(MistralModels.MISTRAL_LARGE_LATEST, "你好");
EmbeddingResponse emb = MistralUtil.embed(MistralModels.MISTRAL_EMBED, "文本");
```

## 模型

| 常量 | 模型 ID |
|---|---|
| `MISTRAL_LARGE_LATEST` | `mistral-large-latest` |
| `MISTRAL_MEDIUM_LATEST` | `mistral-medium-latest` |
| `MISTRAL_SMALL_LATEST` | `mistral-small-latest` |
| `CODESTRAL_LATEST` | `codestral-latest` |
| `MAGISTRAL_MEDIUM_LATEST` | `magistral-medium-latest` |
| `MISTRAL_EMBED` | `mistral-embed` |

## 能力矩阵

| 能力 | 支持 | 说明 |
|---|---|---|
| Chat | ✅ | `/v1/chat/completions`，OpenAI 兼容 |
| Stream | ✅ | SSE `chat.completion.chunk` + `data: [DONE]` |
| Embedding | ✅ | `/v1/embeddings`，model=`mistral-embed` |
| Fine-tuning | ✅ | `/v1/fine_tuning/jobs`（v1.4.0 起声明支持） |
| Models | ✅ | `GET /v1/models`（含 capabilities 能力位） |
| Function Calling | ✅ | 注意 `tool_choice` 取值用 `"any"` 而非 `"required"` |
| 结构化输出 | ✅ | `response_format` |
| Image / Video / Moderation | ❌ | v1.4.0 起调用这些方法会立即抛 `AiException("mistral does not support IMAGE capability")`，不再发请求后 4xx |

## 与 OpenAI 的差异

| 项 | OpenAI | Mistral |
|---|---|---|
| `tool_choice` 强制 | `"required"` | `"any"` |
| 随机种子 | `seed` | `random_seed` |
| 代码补全 | 无 | `POST /v1/fim/completions`（Codestral，本期未适配） |

差异字段可通过 `ChatRequest.extra()` 透传。

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `SURE_AI_MISTRAL_API_KEY` | ✅ | Mistral API Key |
| `SURE_AI_MISTRAL_BASE_URL` | ❌ | 覆盖默认 baseUrl |

## 官方文档

https://docs.mistral.ai
