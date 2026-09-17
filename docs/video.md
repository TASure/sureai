# sureai 视频生成

> 版本：0.2.0-SNAPSHOT | 模块：sure-ai-core + 各平台模块

## 架构概述

视频生成全平台均采用**异步任务模式**（提交任务 → 轮询状态 → 获取结果），sureai 在 SDK 内部完成轮询，对外统一以同步方法 `VideoClient.generate(VideoRequest)` 返回 `VideoResponse`。

```
┌─────────────────────────────────────────────────────────┐
│                    VideoClient (接口)                      │
│  VideoResponse generate(VideoRequest)                     │
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
  OpenAiCompatClient  QwenVideoClient  ZhipuVideoClient ...
  (Sora 异步轮询)     (Wan 异步轮询)   (CogVideoX 异步轮询)
```

**核心模型：**

| 类 | 说明 |
|---|---|
| `VideoRequest` | Builder 模式，必填 model/prompt；字段 duration/size/ratio/n/firstFrameImageUrl/lastFrameImageUrl/negativePrompt/seed/withAudio/resolution/extra |
| `VideoResponse` | record：created + List\<VideoResult\> + rawJson；便捷 firstUrl()/firstCoverUrl() |
| `VideoResult` | record：url + coverImageUrl + b64Json + taskStatus + revisedPrompt |
| `VideoClient` | 接口：`VideoResponse generate(VideoRequest)` |

**轮询参数：** 间隔 2 秒，最大等待 120 秒（各平台客户端内部配置，可通过子类覆盖 `videoPollIntervalMs` / `videoMaxWaitMs` 调整）。

## 快速上手

### Maven 依赖

```xml
<!-- 按需引入平台模块 -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

### 三行代码生成视频

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.VideoResponse;

// 环境变量 SURE_AI_OPENAI_API_KEY 自动读取
VideoResponse resp = OpenAiUtil.video(OpenAiModels.SORA_2, "一只猫咪在草地上奔跑");
System.out.println("视频 URL：" + resp.firstUrl());
```

## 平台接入清单

| 平台 | 模型常量 | 端点 | 鉴权 | 异步模式 | 状态枚举 |
|---|---|---|---|---|---|
| **OpenAI Sora** | `SORA_2` / `SORA_2_PRO` | POST /v1/videos → GET /v1/videos/{id} | Bearer API Key | 是 | queued → in_progress → completed/failed |
| **通义万相 Wan** | `WAN2_6_T2V` / `WAN2_5_T2V` | POST /api/v1/services/aigc/video-generation/video-synthesis → GET /api/v1/tasks/{id} | Bearer API Key + X-DashScope-Async | 是 | PENDING → RUNNING → SUCCEEDED/FAILED/CANCELED |
| **智谱 CogVideoX** | `COGVIDEOX_3` / `COGVIDEOX_2` | POST /api/paas/v4/videos/generations → GET /api/paas/v4/async-result/{id} | Bearer API Key (JWT) | 是 | PROCESSING → SUCCESS/FAIL |
| **火山 Seedance** | `SEEDANCE_2_5` / `SEEDANCE_2_0` | POST /api/v3/contents/generations/tasks → GET /api/v3/contents/generations/tasks/{id} | Bearer API Key | 是 | queued → running → succeeded/failed/cancelled/expired |
| **Azure Sora 2** | `SORA_2` | POST /openai/v1/video/generations/jobs?api-version=preview → GET 同路径/{id} | api-key 头 | 是 | queued → preprocessing → running → processing → succeeded/failed/cancelled |

> **Google Veo 2**：需 Vertex AI OAuth2/gcloud token 鉴权，鉴权复杂度高，暂未接入。
> **百度文心一格**：无公开视频生成 API，暂未接入。
> **OpenAI Sora**：官方标注 API 将于 2026-09-24 关闭、产品已停服，接入仅作兼容参考。

## 各平台使用示例

### OpenAI Sora

```java
VideoResponse resp = OpenAiUtil.video(VideoRequest.builder()
    .model(OpenAiModels.SORA_2)
    .prompt("a cat playing piano")
    .duration(8)
    .size("1280x720")
    .build());
```

### 通义万相 Wan

```java
VideoResponse resp = QwenUtil.video(VideoRequest.builder()
    .model(QwenModels.WAN2_6_T2V)
    .prompt("一只猫咪在草地上奔跑")
    .size("1280*720")
    .duration(10)
    .build());
```

### 智谱 CogVideoX

```java
VideoResponse resp = ZhipuUtil.video(VideoRequest.builder()
    .model(ZhipuModels.COGVIDEOX_3)
    .prompt("a cat playing piano")
    .size("1920x1080")
    .duration(5)
    .withAudio(true)
    .build());
```

### 火山 Seedance

```java
VideoResponse resp = DoubaoUtil.video(VideoRequest.builder()
    .model(DoubaoModels.SEEDANCE_2_5)
    .prompt("一只猫咪在草地上奔跑")
    .ratio("16:9")
    .duration(5)
    .resolution("720p")
    .build());
```

### Azure Sora 2

```java
VideoResponse resp = AzureUtil.video(VideoRequest.builder()
    .model(AzureModels.SORA_2)
    .prompt("a cat playing piano")
    .size("1280x720")
    .duration(5)
    .build());
```

## 异步轮询说明

所有视频生成平台均为异步任务模式，sureai 内部统一处理：

1. **提交任务**：POST 到平台视频生成端点，获取任务 ID
2. **轮询状态**：每隔 2 秒 GET 任务状态，直到终态
3. **提取结果**：成功时从响应中提取视频 URL / 封面图 URL，构造 `VideoResponse`
4. **失败处理**：平台返回 failed 状态时抛 `AiException`（含失败原因）；超过 120 秒未完成抛 `AiTimeoutException`

轮询间隔与超时可通过子类覆盖 `videoPollIntervalMs` / `videoMaxWaitMs` 字段调整。

## 测试策略

所有平台视频生成测试均使用本地 `com.sun.net.httpserver.HttpServer` mock，零真实网络：

- **成功路径**：mock 提交返回 queued，轮询第一次返回 in_progress，第二次返回 completed（含 video_url）
- **失败路径**：轮询返回 failed，断言抛 `AiException`
- **超时路径**：调小轮询间隔/超时，断言抛 `AiTimeoutException`
- **协议断言**：断言请求路径、鉴权头、请求体字段（model/prompt/size/duration 等）

## 相关文档

- [图像生成](images.md)
- [语音 TTS/STT](audio.md)
- [README](../README.md)
