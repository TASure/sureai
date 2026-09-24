# Grok (xAI) 平台

> 模块：`sure-ai-grok` ｜ Base URL：`https://api.x.ai/v1` ｜ 协议：OpenAI 兼容

## 快速开始

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-grok</artifactId>
    <version>1.1.0-SNAPSHOT</version>
</dependency>
```

```java
GrokClient client = new GrokClient(
    AiConfig.builder().apiKey(System.getenv("SURE_AI_GROK_API_KEY")).build()
);
ChatResponse resp = client.chat(ChatRequest.builder()
    .model(GrokModels.GROK_4_3)
    .messages(List.of(ChatMessage.user("你好")))
    .build());
```

或使用便捷入口：

```java
ChatResponse resp = GrokUtil.chat(GrokModels.GROK_4_3, "你好");
```

## 模型

| 常量 | 模型 ID |
|---|---|
| `GROK_4_6` | `grok-4.6` |
| `GROK_4_3` | `grok-4.3` |
| `GROK_4_20_REASONING` | `grok-4-20-reasoning` |
| `GROK_4_1_FAST_REASONING` | `grok-4-1-fast-reasoning` |
| `GROK_3` | `grok-3` |
| `GROK_3_MINI` | `grok-3-mini` |
| `GROK_CODE_FAST_1` | `grok-code-fast-1` |

## 能力矩阵

| 能力 | 支持 | 说明 |
|---|---|---|
| Chat | ✅ | `/v1/chat/completions`，OpenAI 兼容 |
| Stream | ✅ | SSE `chat.completion.chunk` + `data: [DONE]` |
| Embedding | ❌ | xAI 不提供 Embeddings API，`embed()` 抛 `AiException` |
| Models | ✅ | `GET /v1/models` |
| Function Calling | ✅ | OpenAI tools 协议 |
| 思考模式 | ✅ | `reasoning_effort`（low/medium/high/xhigh），经 `ChatRequest.reasoningEffort()` 透传 |
| 结构化输出 | ✅ | `response_format` |

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `SURE_AI_GROK_API_KEY` | ✅ | xAI API Key |
| `SURE_AI_GROK_BASE_URL` | ❌ | 覆盖默认 baseUrl |

## 官方文档

https://docs.x.ai/docs/overview
