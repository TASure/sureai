# 多模态内容块（Multimodal：图像理解 / PDF 输入 / Prompt 缓存）

sureai 用一套**内容块（MessagePart）**架构统一描述一条消息里的混合内容：纯文本、图片、PDF 文档。
各平台把同一套内容块翻译成自家协议，业务代码只面向 core 模型编程。

## 1. 内容块架构

`MessagePart` 是一个 sealed 接口，三个实现：

```
MessagePart (sealed)
   ├── TextPart       文本片段（可挂 Prompt 缓存控制）
   ├── ImagePart      图片片段（URL 或 Base64 内联）
   └── DocumentPart   文档片段（PDF Base64 内联 或 文件 ID 引用）
```

在一条用户消息里用 `ChatMessage.user(List<MessagePart>)` 组合多个片段：

```java
ChatMessage.user(List.of(
    TextPart.of("这张图里有什么？"),
    ImagePart.ofUrl("https://example.com/cat.jpg")
))
```

| 片段 | 工厂方法 | 说明 |
|---|---|---|
| `TextPart` | `TextPart.of(text)` | 纯文本 |
| `TextPart` | `TextPart.ofWithCache(text, CacheControl.ephemeral())` | 带 Prompt 缓存标记 |
| `ImagePart` | `ImagePart.ofUrl(url)` | 公网图片 URL |
| `ImagePart` | `ImagePart.ofBase64(base64, mimeType)` | Base64 内联图片 |
| `DocumentPart` | `DocumentPart.ofBase64(name, mimeType, base64)` | Base64 内联 PDF |
| `DocumentPart` | `DocumentPart.ofFileId(fileId)` | 引用已上传文件 ID |

`ImagePart.resolvedUrl()` 对外统一返回 `data:<mime>;base64,<data>` 形式的地址（URL 形式原样返回），
OpenAI 兼容平台与百度直接把它塞进 `image_url.url`。

## 2. 图像理解

```java
import com.sure.ai.model.*;

ChatRequest req = ChatRequest.builder()
    .model("gpt-4o")
    .messages(List.of(ChatMessage.user(List.of(
        TextPart.of("用一句话描述这张图片。"),
        ImagePart.ofUrl("https://example.com/cat.jpg")   // 或 ImagePart.ofBase64(b64, "image/png")
    ))))
    .build();

ChatResponse resp = OpenAiUtil.chat(req);
System.out.println(resp.firstText());
```

### 各平台图片输入格式对比

| 平台 | 图片序列化形态 | Base64 内联 |
|---|---|---|
| OpenAI 兼容（OpenAI/Azure/Qwen/Zhipu/Doubao/DeepSeek/Moonshot） | `{"type":"image_url","image_url":{"url":resolvedUrl()}}` | `resolvedUrl()` 输出 data URI |
| Google Gemini | `inlineData {"mimeType":...,"data":<裸 base64>}` | 裸 base64（不带 `data:` 前缀） |
| Anthropic | `{"type":"image","source":{"type":"base64","media_type":...,"data":<裸 base64>}}` | 裸 base64 |
| 百度千帆 | `{"type":"image_url","image_url":{"url":resolvedUrl()}}` | data URI |

> 关键差异：OpenAI 兼容 / 百度走 **data URL**（`data:image/png;base64,xxx`）；Gemini / Anthropic 走
> **裸 base64 + 独立 mime 字段**。SDK 在各平台客户端内部自动转换，业务侧统一用 `ImagePart`。

## 3. PDF 文档输入

`DocumentPart` 描述一份 PDF 文档（多页文档理解）：

```java
ChatMessage.user(List.of(
    TextPart.of("总结这份 PDF 的主要结论。"),
    DocumentPart.ofBase64("report.pdf", "application/pdf", base64Pdf)
    // 或 DocumentPart.ofFileId("file-xxx")  // OpenAI 已上传文件 ID
))
```

### PDF 支持矩阵

| 平台 | PDF 序列化形态 | 支持 |
|---|---|---|
| OpenAI | `{"type":"input_file","input_file":{"file_id":...}}` 或 base64 data URI | ✅ |
| Azure | 同 OpenAI | ✅ |
| Google Gemini | `inlineData`（同图片，`mimeType=application/pdf`） | ✅ |
| Anthropic | `{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":...}}` | ✅ |
| 通义千问 | `{"type":"file","file":{"file_data":...}}` | ✅ |
| 百度千帆 | 不支持 PDF 输入，调用时抛 `AiException` | ❌ |

## 4. Prompt 缓存

`CacheControl`（record，目前仅 `CacheControl.ephemeral()`）标记某段文本「可被平台缓存」，
通过 `TextPart.ofWithCache(text, cacheControl)` 挂到文本片段上：

```java
TextPart system = TextPart.ofWithCache(
    "你是一个严格的合同审查助手……（很长的系统提示词）",
    CacheControl.ephemeral());
```

| 平台 | 缓存实现方式 |
|---|---|
| Anthropic | 序列化 TextPart 时自动附加 `cache_control:{"type":"ephemeral"}`，由平台自动计费与命中 |
| Google Gemini | 通过 `ChatRequest.extra("cachedContent", name)` 传入已创建的缓存资源名 |
| OpenAI | **自动缓存**（服务端按前缀自动命中），客户端无需传任何参数 |

> 长系统提示词 / 多轮长上下文场景下，Prompt 缓存可显著降低重复前缀的输入 token 费用。

## 5. 平台对比表

| 平台 | 图像理解 | PDF 文档 | Prompt 缓存 |
|---|---|---|---|
| OpenAI | ✅ | ✅ | ✅ 自动缓存（无需参数） |
| Azure | ✅ | ✅ | ✅ 自动缓存 |
| Anthropic | ✅ | ✅ | ✅ `cache_control: ephemeral` |
| Google Gemini | ✅ | ✅ | ✅ `cachedContent`（extra 传入） |
| 通义千问 | ✅ | ✅ `{"type":"file"}` | ❌ |
| 智谱 GLM | ✅ | ❌ | ❌ |
| 豆包 | ✅ | ❌ | ❌ |
| 百度千帆 | ✅ | ❌ 抛 `AiException` | ❌ |
| DeepSeek / Moonshot | ✅（OpenAI 兼容） | ❌ | ❌ |

## 6. 测试说明

各平台模块的单元测试用本地 `HttpServer` mock 断言：OpenAI 兼容平台的 `image_url` / `input_file`
字段；Gemini 的 `inlineData`（裸 base64、mimeType）；Anthropic 的 `image`/`document` source 结构
与 `cache_control` 标记；百度 PDF 抛 `AiException`。

```bash
mvn -B -pl sure-ai-anthropic -am test
```
