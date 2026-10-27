# 讯飞星火 Spark

## 简介

讯飞星火开放平台的 OpenAI 兼容端点。兼容端点使用短模型名 `lite` / `pro` / `max` / `general`，其中 `lite` 为永久免费模型。

> 星火原生 WebSocket 接口（`wss://spark-api.xf-yun.com/...`，按 domain 区分 generalv3.5 / 4.0Ultra 等）不在本 OpenAI 兼容模块范围内。

## 鉴权方式

`Authorization: Bearer <apiKey>`。**注意：讯飞的 API Key 形如 `APIPath:APIKey`（控制台「APIPath:APIKey」整体）**，调用方应将其整体作为 `apiKey` 传入，由客户端原样放入 Bearer 头。

```bash
export SURE_AI_SPARK_API_KEY="APIPath:APIKey"
```

可选设置 `SURE_AI_SPARK_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://spark-api-open.xf-yun.com/v1
```

兼容引擎拼接 `/chat/completions`，即实际请求 `https://spark-api-open.xf-yun.com/v1/chat/completions`。

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `SparkModels.LITE` | `lite` | 永久免费模型 |
| `SparkModels.PRO` | `pro` | Pro |
| `SparkModels.MAX` | `max` | Max |
| `SparkModels.GENERAL` | `general` | General |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-spark</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = SparkUtil.chat(SparkModels.LITE, "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("APIPath:APIKey").build();
SparkClient client = new SparkClient(config);
ChatResponse resp = client.chat(SparkModels.LITE, "你好！");
client.close();
```

## 流式调用

```java
SparkUtil.chatStream(
    ChatRequest.builder()
        .model(SparkModels.LITE)
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
- **不支持 Embedding**：讯飞 OpenAI 兼容端点仅提供 `/v1/chat/completions`，未在该兼容面提供 `/v1/embeddings`（向量 / 内容审核为星火独立产品或原生接口）；调用 `embed` 时由基类 `guard(Capability.EMBED)` 快速失败，抛出 `AiException`（消息 `"spark does not support EMBED capability"`）。

## 官方文档

- 星火认知大模型 Web API：https://www.xfyun.cn/doc/spark/Web.html
- 批处理 API（模型名对照）：https://www.xfyun.cn/doc/spark/BatchAPI.html
