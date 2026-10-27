# 多模态内容块（Multimodal：图像理解 / PDF 输入 / Prompt 缓存）

sureai 用一套**内容块（MessagePart）**架构统一描述一条消息里的混合内容：纯文本、图片、PDF 文档。
各平台把同一套内容块翻译成自家协议，业务代码只面向 core 模型编程。

## 1. 内容块架构

`MessagePart` 是一个 sealed 接口，三个实现：

```
MessagePart (sealed)
   ├── TextPart       文本片段（可挂 Prompt 缓存控制）
   ├── ImagePart      图片片段（URL 或 Base64 内联）
   ├── DocumentPart   文档片段（PDF Base64 内联 或 文件 ID 引用）
   └── VideoPart      视频片段（URL/文件引用 或 Base64 内联，v2.4.0 新增）
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
| `VideoPart` | `VideoPart.ofUrl(url)` | 视频 URL / Gemini fileUri（v2.4.0） |
| `VideoPart` | `VideoPart.ofBase64(base64, mimeType)` | Base64 内联视频（v2.4.0） |

`ImagePart.resolvedUrl()` 对外统一返回 `data:<mime>;base64,<data>` 形式的地址（URL 形式原样返回），
OpenAI 兼容平台与百度直接把它塞进 `image_url.url`。`VideoPart.resolvedUrl()` 同理，
供 OpenAI 兼容平台（通义千问）塞进 `video_url.url`。

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

## 4. 视频输入（v2.4.0 新增）

`VideoPart` 描述一段视频（视频理解输入）。**注意这是「视频理解输入」，不是「视频生成」**；
视频生成走各平台独立的异步任务客户端（如 `QwenVideoClient`）。

```java
ChatMessage.user(List.of(
    TextPart.of("总结这段视频。"),
    VideoPart.ofUrl("https://example.com/demo.mp4")
    // 或 VideoPart.ofBase64(b64, "video/mp4")
))
```

### 视频输入序列化矩阵（2026 官方文档核实）

| 平台 | 视频序列化形态 | 支持 | 官方依据 |
|---|---|---|---|
| Google Gemini | URL/文件引用 → `{"fileData":{"fileUri":...,"mimeType":"video/mp4"}}`；base64 → `inlineData` | ✅ | ai.google.dev/gemini-api/docs/video-understanding |
| 通义千问 Qwen-VL | `{"type":"video_url","video_url":{"url":resolvedUrl()}}` | ✅（仅部分 Qwen-VL/QVQ/Qwen-Omni 模型） | help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions |
| Anthropic | 仅图像理解，**不支持视频输入** | ❌（已核实） | platform.claude.com/docs/en/build-with-claude/vision |

> Gemini 视频引用的 `fileUri` 通常来自 Files API 上传（返回的 `file.uri`）或 Cloud Storage
> `gs://` 路径；内联 base64 适合 <100MB 的短视频。Qwen-VL 的 `video_url.url` 为公网 URL 或
> Base64 Data URL。

## 5. Prompt 缓存

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

## 6. 平台对比表

| 平台 | 图像理解 | PDF 文档 | 视频输入 | Prompt 缓存 |
|---|---|---|---|---|
| OpenAI | ✅ | ✅ | （原生协议，未在本批适配） | ✅ 自动缓存（无需参数） |
| Azure | ✅ | ✅ | 未核实 | ✅ 自动缓存 |
| Anthropic | ✅ base64/url/file 三种 source | ✅ | ❌ 不支持视频（已核实） | ✅ `cache_control: ephemeral` |
| Google Gemini | ✅ | ✅ | ✅ `fileData`/`inlineData`（已核实） | ✅ `cachedContent`（extra 传入） |
| 通义千问 | ✅ | ✅ `{"type":"file"}` | ✅ `{"type":"video_url"}`（已核实） | ❌ |
| 智谱 GLM | ✅ | ❌ | 未核实 | ❌ |
| 豆包 | ✅ | ❌ | 未核实 | ❌ |
| 百度千帆 | ✅ | ❌ 抛 `AiException` | 未核实 | ❌ |
| DeepSeek / Moonshot | ✅（OpenAI 兼容） | ❌ | 未核实 | ❌ |

## 7. 测试说明

各平台模块的单元测试用本地 `HttpServer` mock 断言：OpenAI 兼容平台的 `image_url` / `input_file`
/ `video_url` 字段；Gemini 的 `inlineData` / `fileData`（裸 base64、mimeType、fileUri）；
Anthropic 的 `image`/`document` source 结构（含 `source.type=url`）、`cache_control` 断点，
以及并行工具结果合并为单条 user 消息的多个 `tool_result` 块；百度 PDF 抛 `AiException`。

```bash
mvn -B -pl sure-ai-anthropic -am test
```
