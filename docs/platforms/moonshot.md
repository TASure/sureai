# Moonshot（Kimi）

## 简介

月之暗面（Moonshot AI）的 Kimi 系列大模型，以长文本处理能力著称。API 协议与 OpenAI 兼容。

## 鉴权方式

Bearer Token 鉴权。在 [Moonshot Platform](https://platform.moonshot.cn/console/api-keys) 创建 API Key。设置环境变量：

```bash
export SURE_AI_MOONSHOT_API_KEY="sk-..."
```

可选设置 `SURE_AI_MOONSHOT_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.moonshot.cn/v1
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `MoonshotModels.KIMI_K2` | `kimi-k2` | Kimi K2 最新模型 |
| `MoonshotModels.KIMI_K2_5` | `kimi-k2.5` | Kimi K2.5 |
| `MoonshotModels.MOONSHOT_V1_8K` | `moonshot-v1-8k` | 8K 上下文 |
| `MoonshotModels.MOONSHOT_V1_32K` | `moonshot-v1-32k` | 32K 上下文 |
| `MoonshotModels.MOONSHOT_V1_128K` | `moonshot-v1-128k` | 128K 长上下文 |
| `MoonshotModels.MOONSHOT_EMBEDDING_V1` | `moonshot-embedding-v1` | 向量模型 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-moonshot</artifactId>
    <version>1.2.1-SNAPSHOT</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = MoonshotUtil.chat("moonshot-v1-8k", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("sk-...").build();
MoonshotClient client = new MoonshotClient(config);
ChatResponse resp = client.chat("moonshot-v1-8k", "你好！");
client.close();
```

## 流式调用

```java
MoonshotUtil.chatStream(
    ChatRequest.builder()
        .model("moonshot-v1-8k")
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

- API 协议与 OpenAI 兼容
- 支持 Embedding（`moonshot-embedding-v1`）
- 支持 Function Calling
- Kimi 系列以超长上下文窗口著称（最高 128K）

## 官方文档

https://platform.moonshot.cn/docs/overview
