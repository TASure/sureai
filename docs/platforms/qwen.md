# 通义千问（Qwen / DashScope）

## 简介

阿里云通义千问大模型服务，通过 DashScope 平台提供。sureai 的 Qwen 模块使用 OpenAI 兼容模式端点。

## 鉴权方式

Bearer Token 鉴权，API Key 以 `sk-` 开头。在 [阿里云百炼](https://dashscope.console.aliyun.com/) 获取 API Key。设置环境变量：

```bash
export SURE_AI_QWEN_API_KEY="sk-..."
```

可选设置 `SURE_AI_QWEN_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://dashscope.aliyuncs.com/compatible-mode/v1
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `QwenModels.QWEN_MAX` | `qwen-max` | 旗舰模型 |
| `QwenModels.QWEN_PLUS` | `qwen-plus` | 均衡模型 |
| `QwenModels.QWEN_TURBO` | `qwen-turbo` | 高速模型 |
| `QwenModels.QWEN_LONG` | `qwen-long` | 长文本模型 |
| `QwenModels.TEXT_EMBEDDING_V3` | `text-embedding-v3` | 向量 V3 |
| `QwenModels.TEXT_EMBEDDING_V2` | `text-embedding-v2` | 向量 V2 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-qwen</artifactId>
    <version>1.2.1-SNAPSHOT</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = QwenUtil.chat("qwen-turbo", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("sk-...").build();
QwenClient client = new QwenClient(config);
ChatResponse resp = client.chat("qwen-turbo", "你好！");
client.close();
```

## 流式调用

```java
QwenUtil.chatStream(
    ChatRequest.builder()
        .model("qwen-turbo")
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

- 使用 DashScope 的 OpenAI 兼容模式端点
- 支持 Embedding（`text-embedding-v3/v2`）
- 支持 Function Calling
- 不同模型计费不同，详见阿里云文档

## 官方文档

https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope
