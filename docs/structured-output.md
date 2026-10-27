# 结构化输出（Structured Output）

sureai 通过 `ChatRequest.responseFormat(Object)` 统一抽象「强制模型输出 JSON」的能力，
并提供 `JsonMapper` 工具类把模型返回的 JSON 文本直接反序列化为 Java record，避免手写字符串解析。

## 1. 架构概述

```
ChatRequest.builder()
    .responseFormat(Object)        ← 统一入口：String 或 JSON Schema 对象
            │
   ┌────────┼───────────────────────────────┐
   ▼        ▼                               ▼
OpenAI 兼容   Gemini                      Anthropic
response_format  generationConfig         无原生字段，
(透传对象/字符串)  .responseMimeType        自动注入 structured_output
                  + responseSchema         强制 tool_use 模拟
```

core 侧只负责在 `ChatRequest` 上挂一个 `Object responseFormat`；各平台客户端在序列化时把它翻译成
自家协议。平台不支持原生结构化字段时（如 Anthropic），SDK 用等价机制（强制工具调用）模拟，
对业务代码仍只表现为「设置了 responseFormat」。

## 2. 快速上手

```java
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.util.JsonMapper;

// 目标强类型 record：字段名与模型输出 JSON 的 key 一致
record CityInfo(String city, String country, int population) {}

ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user(
        "提取城市信息，只输出 JSON：{\"city\":...,\"country\":...,\"population\":...}")))
    .responseFormat("json_object")          // OpenAI 兼容平台
    .build();

ChatResponse resp = OpenAiUtil.chat(req);
JsonObject json = Json.parse(resp.firstText()).getAsJsonObject();
CityInfo info = JsonMapper.fromJson(json, CityInfo.class);
```

## 3. responseFormat 取值形式

| 形式 | 取值 | 适用 |
|---|---|---|
| 字符串 | `"json_object"` / `"text"` | OpenAI 兼容平台、百度 |
| JSON Schema 对象 | `JsonObject` / `Map`，如 `{"type":"json_schema","json_schema":{...}}` | OpenAI 兼容平台、Gemini |

> 注意：OpenAI 要求使用 `json_object` 时，提示词里必须出现「json」字样，否则报错。

## 4. 各平台差异

### 4.1 OpenAI 兼容平台（OpenAI / Azure / Qwen / Zhipu / Doubao / DeepSeek / Moonshot）

直接透传为请求体 `response_format` 字段：

- 字符串 `"json_object"` → `"response_format":"json_object"`（实际等价 `{"type":"json_object"}`）；
- JSON Schema 对象 → `"response_format":{"type":"json_schema",...}`。

### 4.2 Google Gemini

映射到 `generationConfig`：

- `responseFormat` 为 `"application/json"` 或 JSON Schema 对象时，设置
  `responseMimeType="application/json"`；
- 带 schema 时额外设置 `responseSchema`（由 SDK 把 JsonObject 转成 Gemini schema）。

### 4.3 Anthropic

Anthropic Messages API **无原生 `response_format` 字段**，SDK 通过**强制 tool_use** 模拟：
自动注入一个名为 `structured_output` 的工具（其 input_schema 即目标 JSON Schema），并把
`tool_choice` 设为强制调用该工具。业务侧仍只需 `ChatRequest.responseFormat(...)`，
反序列化时取工具入参 JSON 即可。

### 4.4 百度千帆

百度的 `response_format` 为**字符串取值**，仅支持 `"json_object"` / `"text"`；
不支持传入 JSON Schema 对象。

## 5. JsonMapper 强类型反序列化

`com.sure.ai.util.JsonMapper` 是零第三方依赖、面向 Java record 的反射式双向映射工具：

- `fromJson(JsonObject, Class<T>)`：按 record 组件名从 `JsonObject` 同名字段取值并构造实例；
- `toJson(Object)` / `toJsonObject(Object)`：把 record 递归序列化为 `JsonElement` / `JsonObject`。

支持字段类型：`String`、`int/Integer`、`long/Long`、`double/Double`、`boolean/Boolean`、
嵌套 record、`List`（标量或 record 元素）、`Map`。不支持的类型抛 `AiException`。

```java
record Address(String city, String street) {}
record Person(String name, int age, List<String> tags, Address address) {}

JsonObject jo = Json.parse(text).getAsJsonObject();
Person p = JsonMapper.fromJson(jo, Person.class);   // 嵌套 record / List 自动递归
```

> 从 `ChatResponse` 取 JSON 文本后，用 `com.sure.ai.internal.json.Json.parse(text).getAsJsonObject()`
> 解析，再交给 `JsonMapper`。

## 6. 平台对比表

| 平台 | 原生 response_format | 实现方式 | JSON Schema |
|---|---|---|---|
| OpenAI | ✅ | 透传 `response_format` | ✅ |
| Azure | ✅ | 同 OpenAI | ✅ |
| Gemini | ✅ | `generationConfig.responseMimeType` + `responseSchema` | ✅ |
| Anthropic | ❌（无字段） | 自动注入 `structured_output` 工具强制 tool_use | ✅ |
| 通义千问 | ✅ | 透传（OpenAI 兼容） | ✅ |
| 智谱 GLM | ✅ | 透传（OpenAI 兼容） | ✅ |
| 豆包 | ✅ | 透传（OpenAI 兼容） | ✅ |
| 百度千帆 | 字符串取值 | `"json_object"` / `"text"` | ❌ |
| DeepSeek / Moonshot | ✅ | 透传（OpenAI 兼容） | ✅ |

## 7. 测试说明

各平台模块的单元测试用本地 `HttpServer` mock 断言：字符串形式序列化为 `"response_format":"json_object"`，
对象形式序列化为嵌套对象；Gemini 断言 `generationConfig.responseMimeType/responseSchema`；
Anthropic 断言自动注入的 `structured_output` 工具与强制 `tool_choice`；百度断言字符串取值。

```bash
mvn -B -pl sure-ai-core -am test
```
