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

### 鉴权流程

客户端按以下步骤完成鉴权（v1.3.0 起凭证不再出现在 URL 查询串中）：

1. **换取 access_token**：以 `POST application/x-www-form-urlencoded` 请求 `{baseUrl}/oauth/2.0/token`，
   body 中携带 `grant_type=client_credentials`、`client_id`（即 API Key）、`client_secret`（即 Secret Key，
   经 URL 编码）。凭证走 POST body，避免进入代理访问日志与服务器 access log。
2. **业务接口调用**：对话 / Embedding / 微调等业务接口通过 `Authorization: Bearer <access_token>`
   请求头传递 token，不再拼在 URL 查询串上。
3. **token 缓存**：按响应 `expires_in` 缓存，提前 60 秒自动刷新；并发下只刷新一次。

> **TTS / STT 例外**：短语音合成（`tsn.baidu.com/text2audio`）与语音识别（`vop.baidu.com/server_api`）
> 使用独立域名，token 分别以表单字段 `tok` / JSON 字段 `token` 放在 POST body 中，不走 Bearer 头。

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
    <version>1.4.0</version>
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

- **access_token 自动缓存**：客户端以 POST form 方式（`application/x-www-form-urlencoded`）换取 access_token 并缓存（提前 60 秒刷新）；业务接口通过 `Authorization: Bearer` 头传递，凭证不进 URL 查询串
- **model 参数为 URL 路径参数**：与 OpenAI 兼容模式不同，模型名在 URL 中指定
- 支持 Embedding（`embedding-v1`）
- 需同时设置 `SURE_AI_BAIDU_API_KEY` 和 `SURE_AI_BAIDU_SECRET_KEY`

## 官方文档

https://wenxinyiyan.apifox.cn/doc-3253895
