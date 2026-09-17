# Azure OpenAI

## 简介

微软 Azure 云平台托管的 OpenAI 服务。与 OpenAI 官方 API 协议兼容，但 URL 路径结构不同，需指定部署名（deployment）与 api-version。

## 鉴权方式

使用 Azure API Key（非 OpenAI Key）。在 Azure Portal 的 Cognitive Services 中获取。设置环境变量：

```bash
export SURE_AI_AZURE_API_KEY="..."
export SURE_AI_AZURE_RESOURCE="your-resource-name"
```

也可通过 `SURE_AI_AZURE_BASE_URL` 指定完整 baseUrl。

## 默认 Endpoint

baseUrl 为 `null` 时，根据 `SURE_AI_AZURE_RESOURCE` 推导为：

```
https://{resource}.openai.azure.com
```

## 模型 ID

Azure 使用**部署名（deployment）**而非模型 ID 调用。常见部署名参考：

| 参考部署名 | 说明 |
|-----------|------|
| `gpt-4o` | GPT-4o 部署 |
| `gpt-4o-mini` | GPT-4o mini 部署 |
| `gpt-4-turbo` | GPT-4 Turbo 部署 |
| `text-embedding-3-small` | 向量模型部署 |
| `text-embedding-3-large` | 高精度向量部署 |

实际部署名以 Azure Portal 中创建的部署为准。

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-azure</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = AzureUtil.chat("gpt-4o", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

deployment 和 api-version 通过 `extraHeader` 传入：

```java
AiConfig config = AiConfig.builder()
    .apiKey("...")
    .extraHeader("deployment", "gpt-4o")
    .extraHeader("api-version", "2024-10-21")
    .build();
AzureClient client = new AzureClient(config);
ChatResponse resp = client.chat("gpt-4o", "你好！");
client.close();
```

## 流式调用

```java
AzureUtil.chatStream(
    ChatRequest.builder()
        .model("gpt-4o")
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

- **model 参数实为部署名**（deployment），不是模型 ID
- `api-version` 默认 `2024-10-21`，可通过 `extraHeader("api-version", "...")` 覆盖
- `deployment` 可通过 `extraHeader("deployment", "...")` 指定，缺省 `gpt-4o`
- 支持 Embedding（需创建对应向量模型部署）

## 官方文档

https://learn.microsoft.com/en-us/azure/ai-foundry/openai/reference
