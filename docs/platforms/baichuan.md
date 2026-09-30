# 百川 Baichuan

## 简介

百川智能（Baichuan）开放平台，OpenAI 兼容协议。`Baichuan4-Turbo` 为官方 OpenAI 示例默认模型。

## 鉴权方式

`Authorization: Bearer <apiKey>`。设置环境变量：

```bash
export SURE_AI_BAICHUAN_API_KEY="..."
```

可选设置 `SURE_AI_BAICHUAN_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.baichuan-ai.com/v1
```

实际请求 `https://api.baichuan-ai.com/v1/chat/completions`。

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `BaichuanModels.BAICHUAN4_TURBO` | `Baichuan4-Turbo` | 官方 OpenAI 示例默认模型 |
| `BaichuanModels.BAICHUAN4` | `Baichuan4` | 旗舰 |
| `BaichuanModels.BAICHUAN3_TURBO` | `Baichuan3-Turbo` | 上代 Turbo |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-baichuan</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = BaichuanUtil.chat(BaichuanModels.BAICHUAN4_TURBO, "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("...").build();
BaichuanClient client = new BaichuanClient(config);
ChatResponse resp = client.chat(BaichuanModels.BAICHUAN4_TURBO, "你好！");
client.close();
```

## 流式调用

```java
BaichuanUtil.chatStream(
    ChatRequest.builder()
        .model(BaichuanModels.BAICHUAN4_TURBO)
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
- **不支持 Embedding**：百川的向量接口为原生 `/v1/embedding`（单数），与 OpenAI `/v1/embeddings` 路径不一致，未在本兼容端点声明 `EMBED`；调用 `embed` 时由基类 `guard(Capability.EMBED)` 快速失败，抛出 `AiException`（消息 `"baichuan does not support EMBED capability"`）。

## 官方文档

https://platform.baichuan-ai.com/docs/api
