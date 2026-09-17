# Grounding 联网搜索

sureai 对「联网检索增强回答」做了统一抽象：请求侧用 `ChatRequest.builder().grounding(...)` 一个开关，
响应侧统一把引用来源解析到 `ChatResponse.groundingSources()`（`GroundingSource`）。各平台联网协议
分两类范式——**工具式**（注入一个搜索工具）与**布尔参数式**（一个布尔字段打开开关）——SDK 内部自动适配。

## 1. 设计概述

```
ChatRequest.grounding(Object)
   │
   ├─ 工具式：注入搜索工具
   │     OpenAI/Azure/Zhipu/Doubao  → tools 注入 web_search
   │     Gemini                     → googleSearch 工具
   │
   └─ 布尔参数式：
         Qwen  → enable_search:true（移除 web_search 工具）

ChatResponse.groundingSources(): List<GroundingSource(title, url, content)>
```

**两套范式**

- **工具式**：把联网搜索声明为一个工具（OpenAI `web_search`、Gemini `googleSearch`），模型自行决定何时检索；
  响应通过 annotations / `groundingMetadata` 回带来源。
- **布尔参数式**：直接传 `enable_search: true` 打开开关（Qwen/DashScope），无工具声明。

## 2. 快速上手

```java
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.GroundingSource;
import com.sure.ai.openai.OpenAiUtil;

ChatResponse resp = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("今天有哪些科技圈的重要新闻？")))
    .grounding("web_search")          // 或传入平台特定配置对象
    .build());

System.out.println(resp.firstText());
for (GroundingSource s : resp.groundingSources()) {
    System.out.println(s.title() + "  " + s.url());
}
```

> `grounding` 取值可为字符串 `"web_search"`，或平台特定配置对象（OpenAI `{"type":"web_search"}`、
> Gemini googleSearch 工具、通义 `enable_search` 等）。

## 3. 核心模型

### 3.1 GroundingSource

| 字段 | 类型 | 说明 |
|---|---|---|
| `title` | String | 来源标题，可能为 `null` |
| `url` | String | 来源链接，可能为 `null` |
| `content` | String | 引用片段 / 摘要内容，可能为 `null` |

### 3.2 ChatResponse.groundingSources()

返回 `List<GroundingSource>`，无联网来源时为空列表（永不返回 `null`）。

## 4. 各平台配置与来源解析

| 平台 | 注入范式 | 请求映射 | 响应来源解析 |
|---|---|---|---|
| OpenAI / Azure | 工具式 | 注入 `web_search` 工具 | 响应 `annotations` → `GroundingSource` |
| Gemini | 工具式 | 注入 `googleSearch` 工具 | `groundingMetadata` → `GroundingSource` |
| 通义千问 Qwen | 布尔式 | `enable_search:true`（**覆盖序列化，移除 web_search 工具**） | 引用来源 → `GroundingSource` |
| 智谱 GLM / 豆包 | 工具式 | 继承 core，注入 `web_search` 工具 | 响应 annotations → `GroundingSource` |

## 5. 平台对比表

| 平台 | Grounding 联网 | 范式 |
|---|---|---|
| OpenAI | ✅ | 工具式 web_search |
| Azure OpenAI | ✅ | 工具式 web_search |
| Google Gemini | ✅ | googleSearch 工具 |
| 通义千问 Qwen | ✅ | 布尔 `enable_search` |
| 智谱 GLM | ✅ | 工具式 web_search |
| 豆包 | ✅ | 工具式 web_search |
| Anthropic / DeepSeek / Moonshot / Baidu / Ollama | ❌ | 暂未适配 |

各平台联网开关的序列化与来源字段解析均以本地 mock 单测断言，零真实网络。
