# 智谱 AI（GLM）

## 简介

智谱 AI 的 GLM 系列大模型，通过 bigmodel.cn 平台提供。鉴权方式特殊：使用 `id.secret` 格式的 API Key，客户端自动生成 HS256 JWT。

## 鉴权方式

API Key 格式为 `{id}.{secret}`（如 `xxxxxxxx.yyyyyyyy`）。客户端自动将其签名为 HS256 JWT Bearer Token。在 [智谱开放平台](https://open.bigmodel.cn/usercenter/apikeys) 获取 API Key。设置环境变量：

```bash
export SURE_AI_ZHIPU_API_KEY="your-id.your-secret"
```

可选设置 `SURE_AI_ZHIPU_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://open.bigmodel.cn/api/paas/v4
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `ZhipuModels.GLM_4_PLUS` | `glm-4-plus` | GLM-4 Plus 旗舰 |
| `ZhipuModels.GLM_4_FLASH` | `glm-4-flash` | GLM-4 Flash 高速 |
| `ZhipuModels.GLM_4_AIR` | `glm-4-air` | GLM-4 Air 轻量 |
| `ZhipuModels.GLM_4_LONG` | `glm-4-long` | GLM-4 Long 长文本 |
| `ZhipuModels.EMBEDDING_3` | `embedding-3` | 向量模型 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-zhipu</artifactId>
    <version>1.2.1-SNAPSHOT</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = ZhipuUtil.chat("glm-4-flash", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("your-id.your-secret").build();
ZhipuClient client = new ZhipuClient(config);
ChatResponse resp = client.chat("glm-4-flash", "你好！");
client.close();
```

## 流式调用

```java
ZhipuUtil.chatStream(
    ChatRequest.builder()
        .model("glm-4-flash")
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

- **JWT 自动生成**：API Key 为 `id.secret` 格式，客户端自动使用 HS256 签名生成 JWT Bearer Token，无需手动处理
- 支持 Embedding（`embedding-3`）
- 支持 Function Calling
- JWT 过期时间由客户端自动管理

## 官方文档

https://docs.bigmodel.cn/cn/guide/start/model-overview
