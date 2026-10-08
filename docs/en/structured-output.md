# Structured Output

> English page · Chinese source: [../structured-output.md](../structured-output.md).

sureai unifies "force the model to output JSON" behind `ChatRequest.responseFormat(Object)`,
and ships `JsonMapper` to deserialize that JSON straight into a Java record — no manual
string parsing.

## Minimal example

```java
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.util.JsonMapper;

// target record: field names match the JSON keys the model emits
record CityInfo(String city, String country, int population) {}

ChatRequest req = ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user(
            "Extract city info, JSON only: {\"city\":...,\"country\":...,\"population\":...}")))
        .responseFormat("json_object")     // OpenAI-compatible platforms
        .build();

String text = OpenAiUtil.chat(req).firstText();
JsonObject json = Json.parse(text).getAsJsonObject();
CityInfo info = JsonMapper.fromJson(json, CityInfo.class);
```

> OpenAI requires the word "json" to appear in the prompt when using `json_object`,
> otherwise it errors.

## responseFormat forms

| Form | Value | Applies to |
|---|---|---|
| string | `"json_object"` / `"text"` | OpenAI-compatible platforms, Baidu |
| JSON Schema object | `JsonObject` / `Map`, e.g. `{"type":"json_schema","json_schema":{...}}` | OpenAI-compatible, Gemini |

## Per-platform behavior

- **OpenAI-compatible (OpenAI / Azure / Qwen / Zhipu / Doubao / DeepSeek / Moonshot)**: passed through as the request body's `response_format`.
- **Gemini**: mapped to `generationConfig.responseMimeType` (`application/json`) plus `responseSchema`.
- **Anthropic**: no native `response_format` field — the SDK injects a forced `structured_output` tool (`tool_choice` pinned) and returns the tool input JSON.
- **Baidu Qianfan**: string values only (`json_object` / `text`), no schema object.

## JsonMapper

`com.sure.ai.util.JsonMapper` is a zero-dependency, reflection-based bidirectional
mapper for records: `fromJson(JsonObject, Class<T>)` and `toJson(Object)` /
`toJsonObject(Object)`. Supported field types: `String`, `int/Integer`, `long/Long`,
`double/Double`, `boolean/Boolean`, nested records, `List`, `Map`.

```java
record Address(String city, String street) {}
record Person(String name, int age, List<String> tags, Address address) {}

JsonObject jo = Json.parse(text).getAsJsonObject();
Person p = JsonMapper.fromJson(jo, Person.class);   // nested record / List recurse automatically
```

## Next steps

- [MCP Server](./mcp-server.md) · [Agent](./agent.md) · [Quick Start](./quickstart.md)
