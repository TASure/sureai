# Google Gemini

## 简介

Google 的 Gemini 系列模型，通过 Google AI Studio 获取 API Key。sureai 的 Gemini 模块适配其 generateContent API。

## 鉴权方式

API Key 通过 URL 查询参数 `?key=` 传递。在 [Google AI Studio](https://aistudio.google.com/apikey) 创建 API Key。设置环境变量：

```bash
export SURE_AI_GEMINI_API_KEY="AIza..."
```

可选设置 `SURE_AI_GEMINI_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://generativelanguage.googleapis.com/v1beta
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `GeminiModels.GEMINI_2_5_PRO` | `gemini-2.5-pro` | Pro 旗舰 |
| `GeminiModels.GEMINI_2_5_FLASH` | `gemini-2.5-flash` | Flash 均衡 |
| `GeminiModels.GEMINI_2_5_FLASH_LITE` | `gemini-2.5-flash-lite` | Flash Lite 轻量 |
| `GeminiModels.GEMINI_2_0_FLASH` | `gemini-2.0-flash` | 2.0 Flash |
| `GeminiModels.GEMINI_EMBEDDING_001` | `gemini-embedding-001` | 向量模型 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-gemini</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = GeminiUtil.chat("gemini-2.5-flash", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("AIza...").build();
GeminiClient client = new GeminiClient(config);
ChatResponse resp = client.chat("gemini-2.5-flash", "你好！");
client.close();
```

## 流式调用

```java
GeminiUtil.chatStream(
    ChatRequest.builder()
        .model("gemini-2.5-flash")
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

- API Key 通过 URL 查询参数 `?key=` 传递，不使用 Authorization 头
- 支持 Embedding（`gemini-embedding-001`）
- 支持 Function Calling

## 官方文档

https://ai.google.dev/api/rest/v1beta/models/generateContent
