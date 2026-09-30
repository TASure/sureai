# 01.AI 零一万物（Lingyi / Yi）

## 简介

零一万物（Lingyiwanwu / Yi）开放平台，OpenAI 兼容协议。官方声明 API 与 OpenAI 完全兼容。`yi-large` 为旗舰，`yi-lightning` 为高速推理模型。

## 鉴权方式

`Authorization: Bearer <apiKey>`。设置环境变量：

```bash
export SURE_AI_LINGYI_API_KEY="..."
```

可选设置 `SURE_AI_LINGYI_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.lingyiwanwu.com/v1
```

实际请求 `https://api.lingyiwanwu.com/v1/chat/completions`。

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `LingyiModels.YI_LARGE` | `yi-large` | 旗舰 |
| `LingyiModels.YI_MEDIUM` | `yi-medium` | 中量 |
| `LingyiModels.YI_LIGHTNING` | `yi-lightning` | 高速推理 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-lingyi</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = LingyiUtil.chat(LingyiModels.YI_LARGE, "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("...").build();
LingyiClient client = new LingyiClient(config);
ChatResponse resp = client.chat(LingyiModels.YI_LARGE, "你好！");
client.close();
```

## 流式调用

```java
LingyiUtil.chatStream(
    ChatRequest.builder()
        .model(LingyiModels.YI_LARGE)
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
- **不支持 Embedding**：零一万物 OpenAI 兼容文档以 `/v1/chat/completions` 为准，未提供 `/v1/embeddings`；调用 `embed` 时由基类 `guard(Capability.EMBED)` 快速失败，抛出 `AiException`（消息 `"lingyi does not support EMBED capability"`）。

## 官方文档

https://platform.lingyiwanwu.com/docs/api-reference
