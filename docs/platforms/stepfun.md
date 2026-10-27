# 阶跃星辰 StepFun

## 简介

阶跃星辰（StepFun）开放平台，OpenAI 兼容协议。最新旗舰 MoE `step-5-preview`，另有高性价比的 `step-2-mini`。

## 鉴权方式

`Authorization: Bearer <apiKey>`。设置环境变量：

```bash
export SURE_AI_STEPFUN_API_KEY="..."
```

可选设置 `SURE_AI_STEPFUN_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.stepfun.com/v1
```

实际请求 `https://api.stepfun.com/v1/chat/completions`。国际站端点为 `https://api.stepfun.ai/v1`，可通过 `AiConfig.Builder#baseUrl(String)` 覆盖。

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `StepFunModels.STEP_5_PREVIEW` | `step-5-preview` | 最新旗舰 MoE |
| `StepFunModels.STEP_2_MINI` | `step-2-mini` | 推荐高性价比 |
| `StepFunModels.STEP_2_16K` | `step-2-16k` | 长上下文 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-stepfun</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = StepFunUtil.chat(StepFunModels.STEP_2_MINI, "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("...").build();
StepFunClient client = new StepFunClient(config);
ChatResponse resp = client.chat(StepFunModels.STEP_2_MINI, "你好！");
client.close();
```

## 流式调用

```java
StepFunUtil.chatStream(
    ChatRequest.builder()
        .model(StepFunModels.STEP_2_MINI)
        .messages(List.of(ChatMessage.user("讲个笑话")))
        .build(),
    chunk -> {
        if (chunk.deltaText() != null) {
            System.out.print(chunk.deltaText());
        }
    }
);
```

## 注意事项

- 复用 `OpenAiCompatClient`，仅声明 `CHAT` / `CHAT_STREAM` 能力。
- **不支持 Embedding**：阶跃星辰 OpenAI 兼容文档以 `/v1/chat/completions` 为准，未提供 `/v1/embeddings`；调用 `embed` 时由基类 `guard(Capability.EMBED)` 快速失败，抛出 `AiException`（消息 `"stepfun does not support EMBED capability"`）。

## 官方文档

https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create
