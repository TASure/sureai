# OpenAI

## 简介

OpenAI 官方平台，提供 GPT 系列对话模型与 text-embedding 向量模型。sureai 的 OpenAI 模块直接复用 OpenAI 兼容协议。

## 鉴权方式

Bearer Token 鉴权。在 [OpenAI Platform](https://platform.openai.com/api-keys) 创建 API Key，设置环境变量：

```bash
export SURE_AI_OPENAI_API_KEY="sk-..."
```

可选设置 `SURE_AI_OPENAI_BASE_URL` 覆盖默认地址（如使用代理或 Azure 兼容端点）。

## 默认 Endpoint

```
https://api.openai.com/v1
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `OpenAiModels.GPT_4O` | `gpt-4o` | 多模态旗舰 |
| `OpenAiModels.GPT_4O_MINI` | `gpt-4o-mini` | 高性价比小模型 |
| `OpenAiModels.GPT_4_TURBO` | `gpt-4-turbo` | 长上下文前代旗舰 |
| `OpenAiModels.O1` | `o1` | 推理模型 |
| `OpenAiModels.O1_MINI` | `o1-mini` | 轻量推理模型 |
| `OpenAiModels.TEXT_EMBEDDING_3_SMALL` | `text-embedding-3-small` | 轻量向量 |
| `OpenAiModels.TEXT_EMBEDDING_3_LARGE` | `text-embedding-3-large` | 高精度向量 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
// 自动从 SURE_AI_OPENAI_API_KEY 读取
ChatResponse resp = OpenAiUtil.chat("gpt-4o-mini", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder()
    .apiKey("sk-...")
    .organization("org-...")  // 可选
    .build();
OpenAiClient client = new OpenAiClient(config);
ChatResponse resp = client.chat("gpt-4o-mini", "你好！");
client.close();
```

## 流式调用

```java
OpenAiUtil.chatStream(
    ChatRequest.builder()
        .model("gpt-4o-mini")
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

- 当 `AiConfig.organization()` 非空时自动携带 `OpenAI-Organization` 请求头
- 支持 Function Calling（通过 `ChatRequest.tools()`）
- 支持 Embedding（`text-embedding-3-small/large`）

## 官方文档

https://platform.openai.com/docs/api-reference
