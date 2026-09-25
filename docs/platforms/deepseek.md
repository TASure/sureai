# DeepSeek

## 简介

深度求索（DeepSeek）的大语言模型平台，提供 deepseek-chat（V3）和 deepseek-reasoner（R1 推理模型）。API 协议与 OpenAI 兼容。

## 鉴权方式

Bearer Token 鉴权。在 [DeepSeek Platform](https://platform.deepseek.com/api_keys) 创建 API Key。设置环境变量：

```bash
export SURE_AI_DEEPSEEK_API_KEY="sk-..."
```

可选设置 `SURE_AI_DEEPSEEK_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.deepseek.com
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `DeepSeekModels.DEEPSEEK_CHAT` | `deepseek-chat` | V3 通用对话 |
| `DeepSeekModels.DEEPSEEK_REASONER` | `deepseek-reasoner` | R1 深度推理 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-deepseek</artifactId>
    <version>1.2.1</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = DeepSeekUtil.chat("deepseek-chat", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("sk-...").build();
DeepSeekClient client = new DeepSeekClient(config);
ChatResponse resp = client.chat("deepseek-chat", "你好！");
client.close();
```

## 流式调用

```java
DeepSeekUtil.chatStream(
    ChatRequest.builder()
        .model("deepseek-chat")
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

- API 协议与 OpenAI 兼容，复用 `OpenAiCompatClient`
- **暂不支持 Embedding**（DeepSeek 目前未提供向量 API）
- `deepseek-reasoner`（R1）为推理模型，响应可能包含思考过程
- 支持 Function Calling

## 官方文档

https://api-docs.deepseek.com/
