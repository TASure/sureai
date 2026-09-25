# Cohere v2 平台

> 模块：`sure-ai-cohere` ｜ Base URL：`https://api.cohere.com/v2` ｜ 协议：**独立协议（非 OpenAI 兼容）**

## 快速开始

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-cohere</artifactId>
    <version>1.1.0-SNAPSHOT</version>
</dependency>
```

```java
CohereClient client = new CohereClient(
    AiConfig.builder().apiKey(System.getenv("SURE_AI_COHERE_API_KEY")).build()
);
ChatResponse resp = client.chat(ChatRequest.builder()
    .model(CohereModels.COMMAND_R_PLUS)
    .messages(List.of(ChatMessage.user("你好")))
    .build());
```

便捷入口：

```java
ChatResponse resp = CohereUtil.chat(CohereModels.COMMAND_R_PLUS, "你好");
EmbeddingResponse emb = CohereUtil.embed(CohereModels.EMBED_V4, "文本");
RerankResponse reranked = CohereUtil.rerank("什么是重排",
    List.of("文档一", "文档二", "文档三"));
```

## 模型

| 常量 | 模型 ID |
|---|---|
| `COMMAND_A_PLUS` | `command-a-plus-05-2026` |
| `COMMAND_R_PLUS` | `command-r-plus` |
| `COMMAND_R` | `command-r` |
| `EMBED_V4` | `embed-v4.0` |
| `RERANK_V3_5` | `rerank-v3.5` |

## 协议要点（与 OpenAI 的关键差异）

### Chat

- 端点：`POST /v2/chat`（非 `/v1/chat/completions`）
- 请求体：`{model, messages:[{role, content:字符串}], stream, temperature, max_tokens}`
- **响应无 `choices` 数组**，正文在 `message.content[].text`
- `finish_reason` 为大写枚举（COMPLETE/MAX_TOKENS/STOP_SEQUENCE/TOOL_CALL/ERROR/TIMEOUT）
- usage 在 `usage.tokens.input_tokens/output_tokens`

### 流式

- SSE，每条 `data:` 带 `type` 字段区分事件
- **无 `data: [DONE]` 结束符**，以 `type=message-end` 判断结束
- 事件序列：`message-start` → `content-start` → `content-delta`（增量在 `delta.message.content.text`）→ `content-end` → `message-end`

### Embeddings

- 端点：`POST /v2/embed`
- `input_type` **必填**（默认 `search_document`，可选 search_query/classification/clustering）
- 请求体：`{model, input_type, texts:[...], embedding_types:["float"]}`
- 响应向量在 `embeddings.float[[...]]`（非 `data[].embedding`）

### Models 列表

- v2 无统一列表端点，`listModels()` 抛 `AiException`

### Rerank

- 端点：`POST /v2/rerank`
- 请求体：`{model, query, documents:["..."], top_n?}`；documents 当前支持字符串列表（v2 同时支持对象列表，按需扩展）
- `top_n` 可选，仅在请求显式设置时携带，缺省返回全部结果
- 响应：`{model, results:[{index, relevance_score, document:{text}}]}`，`document.text` 还原为命中文档原文
- 便捷入口：`CohereUtil.rerank(query, documents)`（默认模型 `rerank-v3.5`），或 `CohereUtil.rerankClient().rerank(request)`

## 能力矩阵

| 能力 | 支持 | 说明 |
|---|---|---|
| Chat | ✅ | `/v2/chat`，独立协议 |
| Stream | ✅ | SSE 命名事件，无 [DONE] |
| Embedding | ✅ | `/v2/embed`，input_type 必填 |
| Rerank | ✅ | `/v2/rerank`，`rerank-v3.5` |
| Models | ❌ | 无列表 API，抛异常 |
| Function Calling | ✅ | tools 协议 |
| 结构化输出 | ✅ | `response_format` |

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `SURE_AI_COHERE_API_KEY` | ✅ | Cohere API Key |

## 官方文档

- Chat：https://docs.cohere.com/reference/chat
- 流式：https://docs.cohere.com/docs/streaming
- Embed：https://docs.cohere.com/reference/embed
- Rerank：https://docs.cohere.com/reference/rerank
