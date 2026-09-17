# Realtime 实时语音对话

sureai 对基于 WebSocket 的实时全双工语音对话做了统一抽象：无论平台握手协议与事件路由如何，
对业务代码都暴露同一个 `RealtimeClient`（`connect` / `sendAudio` / `sendText` / `close` / `isConnected`），
事件通过 `RealtimeEventListener` 异步回调，屏蔽平台差异。

## 1. 架构概述

```
┌──────────────────────────────────────────────────────────────────────┐
│              RealtimeClient（接口）                                    │
│   connect() / sendAudio(byte[]) / sendText(String) / close()          │
│   isConnected()                                                       │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ implements
              ┌─────────────────┴───────────────────┐
              │  AbstractRealtimeClient（通用 WS 逻辑） │
              │  · 建连循环 + 帧分发                    │
              │  · sendAudio → base64 → 平台事件        │
              │  · 子类覆盖 buildUri()/handleMessage()  │
              └─────────────────┬───────────────────┘
                                │ 依赖（可注入，便于 mock）
              ┌─────────────────┴───────────────────┐
              │  RealtimeConnector                    │
              │  WebSocket connect(URI, Listener)      │
              │  · DefaultRealtimeConnector（生产）    │
              │  · FakeRealtimeConnector（测试）       │
              └───────────────────────────────────────┘
```

**设计要点**

- **全双工语义**：建连后可同时收发音频与文本；音频分片由 SDK 内部按 base64 编码并封装为平台协议事件。
- **连接器可注入**：`RealtimeConnector` 把「如何建立 WebSocket」抽成接口，生产用
  `DefaultRealtimeConnector`（JDK `java.net.http.WebSocket` 默认实现），测试用
  `FakeRealtimeConnector` + `FakeWebSocket`，**零真实网络**即可断言事件路由。
- **事件归一**：`RealtimeEventListener` 统一暴露 `onTranscript`（转写文本）/ `onAudio`（回复音频分片）/
  `onError` / `onClose` / `onEvent`（未识别类型的原始事件兜底）。
- **零第三方依赖**：WebSocket 直接复用 JDK 21 `java.net.http.WebSocket`。

## 2. 快速上手

引入 OpenAI 模块并设置 `SURE_AI_OPENAI_API_KEY`：

```java
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.openai.OpenAiRealtimeClient;
import com.sure.ai.openai.OpenAiUtil;

RealtimeEventListener listener = new RealtimeEventListener() {
    @Override public void onTranscript(String text) { System.out.println("转写: " + text); }
    @Override public void onAudio(byte[] audio)      { /* 播放/累积回复音频 */ }
    @Override public void onError(String error)       { System.err.println(error); }
    @Override public void onClose()                  { System.out.println("closed"); }
    @Override public void onEvent(String type, String raw) { /* 兜底 */ }
};

OpenAiRealtimeClient client = OpenAiUtil.realtimeClient("gpt-4o-realtime-preview", listener);
client.connect();                 // 建立真实 WebSocket 连接
client.sendText("你好，介绍一下你自己");
// client.sendAudio(pcmChunks);   // 持续上行音频分片
// ... 通话期间 listener 异步回调 ...
client.close();
```

> `realtimeClient(model, listener)` 为单例：首次以 `(model, listener)` 构造，之后重复调用返回同一实例；
> 测试清理用 `OpenAiUtil.resetRealtimeClient()`。其他平台分别由 `GeminiUtil` / `QwenUtil` /
> `ZhipuUtil` / `DoubaoUtil` 提供同名单例方法。

## 3. 核心 API

### 3.1 RealtimeClient

| 方法 | 说明 |
|---|---|
| `connect()` | 建立 WebSocket 连接 |
| `sendAudio(byte[] audio)` | 发送音频分片（内部 base64 编码 + 平台事件封装） |
| `sendText(String text)` | 发送文本（对话式输入 / 指令） |
| `close()` | 关闭连接 |
| `isConnected()` | 是否已连接 |

### 3.2 RealtimeEventListener

| 回调 | 触发时机 |
|---|---|
| `onTranscript(String text)` | 收到语音转写文本 |
| `onAudio(byte[] audio)` | 收到模型回复的音频分片 |
| `onError(String error)` | 发生错误 |
| `onClose()` | 连接关闭 |
| `onEvent(String type, String rawJson)` | 未识别类型的原始事件兜底 |

## 4. 各平台 WebSocket 端点与鉴权对比

| 平台 | WSS 端点 | 鉴权方式 | 事件路由要点 |
|---|---|---|---|
| OpenAI | `wss://api.openai.com/v1/realtime?model=<model>` | 握手头 `Authorization: Bearer <key>` | `response.output_audio.delta` 等 type 路由 |
| Gemini | `wss://generativelanguage.googleapis.com/ws/...BidiGenerateContent?key=<key>` | `?key=` 查询参数 | 首连需先发 `setup`；`serverContent` 路由 |
| 通义千问 Qwen | `wss://dashscope.aliyuncs.com/api-ws/v1/inference?model=<model>` | 握手头 `Authorization: Bearer <key>` | `output.*` 路由 |
| 智谱 GLM | `wss://open.bigmodel.cn/api/paas/v4/realtime?model=<model>` | `id.secret` 签发 HS256 JWT → `Authorization: Bearer <jwt>` | 标准 realtime 事件 |
| 豆包 | `wss://openspeech.bytedance.com/api/v3/auc/realtime` | 握手头 `X-Api-Key: <key>` + `X-Api-Resource-Id: <id>`（可 `extraHeaders` 覆盖） | 火山实时对话协议 |

各平台 `Util` 均提供 `realtimeClient(model, listener)` 单例与 `resetRealtimeClient()`。

## 5. 平台对比表

| 平台 | Realtime | 说明 |
|---|---|---|
| OpenAI | ✅ | `OpenAiRealtimeClient` |
| Gemini | ✅ | `GeminiRealtimeClient`（首连 setup） |
| 通义千问 Qwen | ✅ | `QwenRealtimeClient`（DashScope） |
| 智谱 GLM | ✅ | `ZhipuRealtimeClient`（JWT 鉴权） |
| 豆包 | ✅ | `DoubaoRealtimeClient`（X-Api-Key + Resource-Id） |
| Azure / Anthropic / DeepSeek / Moonshot / Baidu / Ollama | ❌ | 暂未开放实时语音 WebSocket |

## 6. 测试策略

Realtime 单测**不连接真实网络**：通过注入 `FakeRealtimeConnector` 返回 `FakeWebSocket`，
直接调用 `handleMessage(rawJson)` 喂入各平台协议样本，断言是否正确回调到
`onTranscript` / `onAudio` / `onError` / `onEvent`，覆盖 5 个平台的事件路由差异。

```bash
mvn -B -pl sure-ai-openai -am test
```
