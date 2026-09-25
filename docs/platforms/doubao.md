# 火山引擎豆包（Doubao / Ark）

## 简介

字节跳动火山引擎的豆包大模型服务，通过方舟（Ark）平台提供。API 协议与 OpenAI 兼容。

## 鉴权方式

Bearer Token 鉴权。在 [火山引擎方舟控制台](https://console.volcengine.com/ark/) 创建 API Key。设置环境变量：

```bash
export SURE_AI_DOUBAO_API_KEY="..."
```

可选设置 `SURE_AI_DOUBAO_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://ark.cn-beijing.volces.com/api/v3
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `DoubaoModels.DOUBAO_1_5_PRO_32K` | `doubao-1-5-pro-32k` | 1.5 Pro 32K |
| `DoubaoModels.DOUBAO_1_5_LITE_32K` | `doubao-1-5-lite-32k` | 1.5 Lite 32K |
| `DoubaoModels.DOUBAO_PRO_32K` | `doubao-pro-32k` | Pro 32K |
| `DoubaoModels.DOUBAO_PRO_4K` | `doubao-pro-4k` | Pro 4K |
| `DoubaoModels.DOUBAO_EMBEDDING_TEXT` | `doubao-embedding-text-240715` | 向量模型 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-doubao</artifactId>
    <version>1.2.1</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = DoubaoUtil.chat("doubao-1-5-lite-32k", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("...").build();
DoubaoClient client = new DoubaoClient(config);
ChatResponse resp = client.chat("doubao-1-5-lite-32k", "你好！");
client.close();
```

## 流式调用

```java
DoubaoUtil.chatStream(
    ChatRequest.builder()
        .model("doubao-1-5-lite-32k")
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

- **model 参数可传接入点 ID**（`ep-xxx`），也可直接传模型名
- API 协议与 OpenAI 兼容
- 支持 Embedding（`doubao-embedding-text-240715`）
- 支持 Function Calling
- 需在方舟控制台先创建推理接入点

## 官方文档

https://www.volcengine.com/docs/82379/1824718
