# sureai 语音（TTS/STT）

> 版本：1.4.0 | 模块：sure-ai-core + 各平台模块

## 架构概述

sureai 语音能力通过 `AudioClient` 接口统一封装，含两个方法族：

- **TTS（语音合成）**：`TtsResponse synthesize(TtsRequest)` — 文本 → 二进制音频或音频 URL
- **STT（语音识别/转录）**：`SttResponse transcribe(SttRequest)` — 音频二进制 → 转写文本（含段/词级时间戳）

```
┌─────────────────────────────────────────────────────────┐
│                    AudioClient (接口)                      │
│  TtsResponse synthesize(TtsRequest)                       │
│  SttResponse transcribe(SttRequest)                       │
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
  OpenAiCompatClient  BaiduClient    DoubaoTtsClient ...
  (TTS二进制/STT multipart)  (TTS form-data/STT base64)  (TTS base64/STT 异步)
```

**核心模型：**

| 类 | 说明 |
|---|---|
| `TtsRequest` | Builder，必填 model/input/voice；字段 responseFormat/speed/volume/pitch/sampleRate/instructions/extra |
| `TtsResponse` | record：audio(byte[]) + format + url + rawJson；二进制响应时 audio 非空，URL 响应时 url 非空；便捷 audioLength() |
| `SttRequest` | Builder，必填 model/audioData(byte[])；字段 fileName/contentType/language/responseFormat/temperature/prompt/extra |
| `SttResponse` | record：text + language + duration + List\<Segment\> + List\<Word\> + rawJson |
| `Segment` | record：id + start + end + text + List\<Word\>（段级时间戳） |
| `Word` | record：word + start + end（词级时间戳） |

**HTTP 引擎扩展：** `AbstractAiClient` 新增 `doPostBinary`（POST JSON 返回二进制，用于 TTS）和 `doPostMultipart`（multipart/form-data 上传，用于 STT），零第三方依赖。

## 快速上手

### TTS（文本→音频）

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.TtsResponse;

TtsResponse resp = OpenAiUtil.tts(OpenAiModels.TTS_1, "你好，世界", "alloy");
byte[] audio = resp.audio();  // 二进制音频，可直接保存为 mp3
System.out.println("音频大小：" + resp.audioLength() + " bytes");
```

### STT（音频→文本）

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.SttResponse;
import java.nio.file.Files;
import java.nio.file.Paths;

byte[] audioData = Files.readAllBytes(Paths.get("speech.mp3"));
SttResponse resp = OpenAiUtil.stt(OpenAiModels.WHISPER_1, audioData);
System.out.println("转写文本：" + resp.text());
```

## TTS 平台接入清单

| 平台 | 模型常量 | 端点 | 鉴权 | 请求体 | 响应形态 |
|---|---|---|---|---|---|
| **OpenAI** | `TTS_1` / `TTS_1_HD` / `GPT_4O_MINI_TTS` | POST /v1/audio/speech | Bearer API Key | JSON（model/input/voice/speed） | 二进制音频流 |
| **通义 CosyVoice** | `COSYVOICE_V3_5_PLUS` / `COSYVOICE_V3_5_FLASH` | POST /api/v1/services/audio/tts/SpeechSynthesizer | Bearer API Key | 嵌套 JSON（input.text/voice/format/sample_rate） | JSON 含 audio.url（24h 有效） |
| **智谱 GLM-TTS** | `GLM_TTS` | POST /api/paas/v4/audio/speech | Bearer API Key (JWT) | JSON（model/input/voice/speed） | 二进制音频流 |
| **火山豆包** | `SEED_TTS_2_0` | POST /api/v3/tts/create | X-Api-Key + X-Api-Resource-Id 头 | 嵌套 JSON（req_params.text/speaker/audio_params） | JSON 含 base64 data 分片 |
| **百度** | 发音人 per 参数 | POST https://tsn.baidu.com/text2audio | access_token 作 tok 表单字段 | x-www-form-urlencoded（tex/tok/per/spd/vol） | 二进制音频流 |
| **Azure Speech** | `TTS_VOICE_XIAOXIAO` 等 | POST /cognitiveservices/v1 | Ocp-Apim-Subscription-Key 头 | SSML XML + X-Microsoft-OutputFormat 头 | 二进制音频流 |

### TTS 响应形态说明

TTS 平台响应分两派：
- **二进制直返**（OpenAI / 智谱 / 百度 / Azure）：`TtsResponse.audio()` 非空，可直接保存为音频文件
- **URL 返回**（通义 CosyVoice）：`TtsResponse.url()` 非空，URL 通常 24 小时有效，需及时下载转存
- **Base64 分片**（火山豆包）：SDK 内部解码 base64 为二进制，`TtsResponse.audio()` 非空

## STT 平台接入清单

| 平台 | 模型常量 | 端点 | 鉴权 | 请求格式 | 响应 |
|---|---|---|---|---|---|
| **OpenAI Whisper** | `WHISPER_1` / `GPT_4O_TRANSCRIBE` | POST /v1/audio/transcriptions | Bearer API Key | multipart/form-data（file/model/language） | JSON（text/segments/words） |
| **通义 Qwen-ASR** | `QWEN3_ASR_FLASH` | POST /compatible-mode/v1/chat/completions | Bearer API Key | JSON（messages 含 input_audio data URI） | JSON（choices[0].message.content） |
| **智谱 GLM-ASR** | `GLM_ASR_2512` | POST /api/paas/v4/audio/transcriptions | Bearer API Key (JWT) | multipart/form-data | JSON（text） |
| **火山豆包** | `VOLC_BIGASR_AUC` | POST /api/v3/auc/bigmodel/submit → POST /query | X-Api-Key + X-Api-Resource-Id 头 | JSON（audio.url 为 data URI） | JSON（result.text/utterances） |
| **百度** | dev_pid 参数（1537 普通话） | POST https://vop.baidu.com/server_api | access_token 作 token 字段 | JSON（speech base64 + len + format/rate） | JSON（result[]） |
| **Azure Speech** | 无模型 ID（服务端路由） | POST /stt/speech/recognition/conversation/cognitiveservices/v1 | Ocp-Apim-Subscription-Key 头 | 二进制 body（Content-Type audio/wav） | JSON（DisplayText） |

### STT 上传格式说明

STT 平台上传格式分四派：
- **multipart/form-data**（OpenAI / 智谱）：SDK 内部构造 multipart body，file 字段传音频二进制
- **JSON base64**（百度）：SDK 内部将音频 base64 编码放入 speech 字段
- **二进制 body**（Azure）：音频二进制直接放 HTTP Body，Content-Type 指定格式
- **URL/data URI**（通义 / 火山）：SDK 内部将音频 base64 编码为 data URI 放入 audio.url / input_audio.data

## 各平台使用示例

### OpenAI TTS + STT

```java
// TTS
TtsResponse tts = OpenAiUtil.tts(OpenAiModels.TTS_1, "hello world", "nova");
byte[] audio = tts.audio();

// STT
SttResponse stt = OpenAiUtil.stt(SttRequest.builder()
    .model(OpenAiModels.WHISPER_1)
    .audioData(audio)
    .fileName("speech.mp3")
    .contentType("audio/mpeg")
    .language("en")
    .responseFormat("verbose_json")
    .build());
System.out.println(stt.text());
System.out.println("段数：" + stt.segments().size());
```

### 百度 TTS + STT

```java
// TTS（百度无 model 概念，用 per 选发音人）
TtsResponse tts = BaiduUtil.tts("default", "你好，世界", BaiduModels.TTS_PER_XIAOMEI);

// STT
byte[] pcmData = Files.readAllBytes(Paths.get("speech.pcm"));
SttResponse stt = BaiduUtil.stt("default", pcmData);
System.out.println(stt.text());
```

### 通义 CosyVoice TTS

```java
TtsResponse resp = QwenUtil.tts(TtsRequest.builder()
    .model(QwenModels.COSYVOICE_V3_5_PLUS)
    .input("你好，世界")
    .voice("longanhuan_v3.6")
    .responseFormat("mp3")
    .sampleRate(22050)
    .build());
System.out.println("音频 URL：" + resp.url());  // 24h 有效
```

## 测试策略

所有平台语音测试均使用本地 `HttpServer` mock，零真实网络：

- **TTS 成功**：mock 返回二进制音频（Content-Type: audio/mpeg），断言 audioLength > 0、请求体字段正确
- **TTS 错误**：mock 返回 JSON 错误（百度/Azure），断言抛异常
- **STT 成功**：mock 返回 JSON（含 text/segments/words），断言转写文本正确、请求为 multipart 或含 base64
- **协议断言**：断言鉴权头（Bearer / X-Api-Key / api-key / Ocp-Apim-Subscription-Key）、请求路径、Content-Type

## 相关文档

- [图像生成](images.md)
- [视频生成](video.md)
- [README](../README.md)
