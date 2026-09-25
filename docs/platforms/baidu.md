# 百度千帆（文心 ERNIE）

## 简介

百度智能云千帆大模型平台，提供文心一言（ERNIE）系列模型。鉴权方式特殊：需同时提供 API Key 与 Secret Key，客户端自动获取并缓存 access_token。

## 鉴权方式

需同时设置 API Key 和 Secret Key。在 [百度智能云控制台](https://console.bce.baidu.com/ai/) 创建应用获取。设置环境变量：

```bash
export SURE_AI_BAIDU_API_KEY="..."
export SURE_AI_BAIDU_SECRET_KEY="..."
```

可选设置 `SURE_AI_BAIDU_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://aip.baidubce.com
```

## 模型 ID

百度千帆的模型名通过 URL 路径参数传递（非 OpenAI 的 body model 字段）。

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `BaiduModels.ERNIE_4_0_TURBO_8K` | `ernie-4.0-turbo-8k` | ERNIE 4.0 Turbo |
| `BaiduModels.ERNIE_3_5_8K` | `ernie-3.5-8k` | ERNIE 3.5 |
| `BaiduModels.ERNIE_SPEED_128K` | `ernie-speed-128k` | ERNIE Speed 长上下文 |
| `BaiduModels.ERNIE_LITE_8K` | `ernie-lite-8k` | ERNIE Lite 轻量 |
| `BaiduModels.EMBEDDING_V1` | `embedding-v1` | 向量模型 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-baidu</artifactId>
    <version>1.2.1</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = BaiduUtil.chat("ernie-3.5-8k", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

Secret Key 通过 `extraHeader` 传入：

```java
AiConfig config = AiConfig.builder()
    .apiKey("your-api-key")
    .extraHeader(BaiduClient.SECRET_KEY_HEADER, "your-secret-key")
    .build();
BaiduClient client = new BaiduClient(config);
ChatResponse resp = client.chat("ernie-3.5-8k", "你好！");
client.close();
```

也可使用便捷初始化：

```java
BaiduUtil.init("your-api-key", "your-secret-key");
```

## 流式调用

```java
BaiduUtil.chatStream(
    ChatRequest.builder()
        .model("ernie-3.5-8k")
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

- **access_token 自动缓存**：客户端自动用 API Key + Secret Key 换取 access_token 并缓存，过期自动刷新
- **model 参数为 URL 路径参数**：与 OpenAI 兼容模式不同，模型名在 URL 中指定
- 支持 Embedding（`embedding-v1`）
- 需同时设置 `SURE_AI_BAIDU_API_KEY` 和 `SURE_AI_BAIDU_SECRET_KEY`

## 官方文档

https://wenxinyiyan.apifox.cn/doc-3253895
