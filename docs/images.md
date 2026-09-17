# 图像生成（Image Generation）

sureai 对文生图（Text-to-Image）能力做了统一抽象：无论平台是**同步直出**还是**异步任务轮询**，对业务代码都暴露同一个 `ImageClient#generate(ImageRequest)` 同步方法。

## 1. 架构概述

`ImageClient` 是顶层抽象接口，只有一个核心方法 `generate(ImageRequest)` 与一个便捷重载 `generate(model, prompt)`。各平台客户端按需实现该接口，把平台协议差异（请求结构、鉴权、同步/异步）封装在内部。

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ImageClient (接口)                            │
│   ImageResponse generate(ImageRequest req)                         │
│   default  generate(String model, String prompt)                    │
└──────────────┬───────────────┬───────────────┬─────────────────────┘
               │               │               │
   ┌───────────┴──────┐  ┌─────┴─────────┐  ┌─┴──────────────────┐
   │ OpenAiCompatClient│  │ GeminiClient  │  │ QwenImageClient    │
   │ /images/generations│ │ generateContent│ │ 提交→轮询 /tasks   │
   │ (同步, OpenAI协议) │ │ (同步,内联图)  │ │ (异步, DashScope)  │
   └───────┬──────────┘  └───────────────┘  └────────────────────┘
           │ 继承
   ┌───────┼────────────┬─────────────┐
   ▼       ▼            ▼             ▼
 OpenAiClient AzureClient ZhipuClient (其他 OpenAI 兼容网关)
```

**设计要点**

- **同步语义统一**：通义万相、文心一格等异步平台在 `generate()` 内部完成「提交任务 → 轮询状态 → 提取 URL」全链路，对外仍同步返回，调用方无需感知 task_id。
- **结果归一**：每个平台的返回都映射为 `ImageResult(url, b64Json, revisedPrompt)`——OpenAI DALL·E 3 可能同时返回 `url`/`b64_json` 与 `revised_prompt`；Gemini 只返回 Base64 内联数据；通义万相只返回 URL。
- **零第三方依赖**：HTTP 基于 `java.net.http.HttpClient`，JSON 为内置轻量实现，无 OkHttp/Gson。

## 2. 快速上手

引入 OpenAI 模块（其他平台替换 artifactId 即可）：

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>${sureai.version}</version>
</dependency>
```

三行生成图像（自动读取环境变量 `SURE_AI_OPENAI_API_KEY`）：

```java
ImageResponse resp = OpenAiUtil.image(OpenAiModels.DALL_E_3, "一只可爱的小猫咪坐在窗台上");
String url = resp.firstUrl();   // 或 resp.firstB64() 取 Base64
System.out.println(url);
```

## 3. 核心 API

### 3.1 ImageRequest

Builder 模式，`model` 与 `prompt` 必填，其余字段平台不支持时自动忽略：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | String | 是 | 图像模型 ID |
| `prompt` | String | 是 | 提示词 |
| `n` | Integer | 否 | 生成数量 |
| `size` | String | 否 | 尺寸，如 `1024x1024`（DashScope 自动兼容为 `1024*1024`） |
| `quality` | String | 否 | 质量，如 `standard` / `hd` |
| `style` | String | 否 | 风格，如 `vivid` / `natural` |
| `responseFormat` | String | 否 | `url` 或 `b64_json`（OpenAI 协议专用） |
| `user` | String | 否 | 用户标识（OpenAI 协议专用） |
| `extra` | Map | 否 | 透传平台专属参数 |

静态便捷方法：`ImageRequest.of(model, prompt)`。

### 3.2 ImageResponse

| 字段 | 类型 | 说明 |
|---|---|---|
| `created` | long | 创建时间戳（平台未提供时为 0） |
| `data` | List\<ImageResult\> | 生成结果列表 |
| `rawJson` | String | 原始响应 JSON，便于调试与提取扩展字段 |

便捷方法：`firstUrl()`、`firstB64()`，无结果时返回 `null`。

### 3.3 ImageResult

| 字段 | 类型 | 说明 |
|---|---|---|
| `url` | String | 图像访问 URL（可能为 null） |
| `b64Json` | String | Base64 图像数据（可能为 null） |
| `revisedPrompt` | String | 平台修订后的提示词（如 DALL·E 3 的 `revised_prompt`，可能为 null） |

工厂方法：`ImageResult.of(url, b64, revised)`、`ofUrl(url)`、`ofB64(b64)`。

## 4. 各平台配置

### 4.1 OpenAI（同步）

- 默认 baseUrl：`https://api.openai.com/v1`
- 鉴权：`Authorization: Bearer <apiKey>`
- 环境变量：`SURE_AI_OPENAI_API_KEY`（可选 `SURE_AI_OPENAI_BASE_URL`）
- 模型常量：`OpenAiModels.DALL_E_3`、`DALL_E_2`
- 同步直出 `/images/generations`，支持 URL 与 b64_json 两种响应格式，DALL·E 3 返回 `revised_prompt`

```java
ImageResponse resp = OpenAiUtil.image(OpenAiModels.DALL_E_3, "一只猫");
```

### 4.2 Azure OpenAI（同步）

- baseUrl：Azure 部署终结点（如 `https://<resource>.openai.azure.com/openai/deployments/<deployment>`）
- 鉴权：API Key 请求头（Azure 托管）
- 环境变量：`SURE_AI_AZURE_API_KEY`、`SURE_AI_AZURE_BASE_URL`
- 模型常量：`AzureModels.DALL_E_3`（对应 Azure 部署名）
- 同步，复用 OpenAI 兼容 `/images/generations` 协议

```java
ImageResponse resp = AzureUtil.image(AzureModels.DALL_E_3, "一只猫");
```

### 4.3 通义万相 Qwen（DashScope 异步，内部轮询）

- 默认 baseUrl：`https://dashscope.aliyuncs.com`
- 鉴权：`Authorization: Bearer <apiKey>`
- 环境变量：`SURE_AI_QWEN_API_KEY`（可选 `SURE_AI_QWEN_BASE_URL`）
- 模型常量：`QwenModels.WANX_V1`
- 异步：提交任务时附加 `X-DashScope-Async: enable` 头，随后轮询 `/api/v1/tasks/{task_id}`

```java
ImageResponse resp = QwenUtil.image(QwenModels.WANX_V1, "一只猫");
```

### 4.4 智谱 Zhipu（同步）

- 默认 baseUrl：`https://open.bigmodel.cn/api/paas/v4`
- 鉴权：`Authorization: Bearer <apiKey>`（`id.secret` 格式）
- 环境变量：`SURE_AI_ZHIPU_API_KEY`（可选 `SURE_AI_ZHIPU_BASE_URL`）
- 模型常量：`ZhipuModels.COGVIEW_3`、`COGVIEW_3_PLUS`、`COGVIEW_4`、`COGVIEW_3_FLASH`
- 同步直出，复用 OpenAI 兼容 `/images/generations` 协议

```java
ImageResponse resp = ZhipuUtil.image(ZhipuModels.COGVIEW_3, "一只猫");
```

### 4.5 百度千帆 Baidu

- 文心一格图像生成走异步任务协议（提交任务 → 轮询任务状态），规划中对接。
- 环境变量：`SURE_AI_BAIDU_API_KEY` / Secret Key（OAuth2 换 access_token）。
- 接入后将同样以 `BaiduUtil.image(model, prompt)` 同步方法暴露，无需业务代码感知轮询。

### 4.6 Google Gemini（同步，内联 Base64）

- 默认 baseUrl：`https://generativelanguage.googleapis.com/v1beta`
- 鉴权：API Key 作为 `?key=` 查询参数
- 环境变量：`SURE_AI_GEMINI_API_KEY`（可选 `SURE_AI_GEMINI_BASE_URL`）
- 模型常量：`GeminiModels.GEMINI_2_0_FLASH_EXP`（实验性文生图）、`IMAGEN_3`（Vertex AI）
- 协议：复用 `generateContent`，在 `generationConfig` 中设置 `responseModalities=["IMAGE","TEXT"]`；响应图像位于 `candidates[0].content.parts[].inlineData.data`（Base64）

```java
ImageResponse resp = GeminiUtil.image(GeminiModels.GEMINI_2_0_FLASH_EXP, "一只猫");
String b64 = resp.firstB64();
```

## 5. 异步轮询说明

通义万相（DashScope）采用异步任务模式，`QwenImageClient#generate()` 内部流程：

1. **提交任务**：POST 文生图接口，附加 `X-DashScope-Async: enable` 头，响应 `output.task_id`。
2. **轮询状态**：GET `/api/v1/tasks/{task_id}`，读取 `output.task_status`。
3. **状态收敛**：
   - `SUCCEEDED`：从 `output.results[].url` 提取图像 URL，封装为 `ImageResult.ofUrl(...)`。
   - `FAILED`：读取 `output.message`，抛出 `AiApiException`。
   - 其他（`PENDING`/`RUNNING`）：继续等待。
4. **轮询策略**：间隔 `2000ms`，总超时 `120000ms`（常量 `POLL_INTERVAL_MS` / `MAX_WAIT_MS`，可在模块内调整）；超时抛 `AiTimeoutException`，可中断。

文心一格（百度）为同类异步模式，接入后采用相同的「提交 → 轮询 → 提取」封装，业务侧接口不变。

## 6. 平台对比表

| 平台 | 同步/异步 | 响应格式 | 尺寸支持 | quality | n > 1 |
|---|---|---|---|---|---|
| OpenAI DALL·E | 同步 | URL / b64_json | 1024x1024 等 | standard / hd | DALL·E 2 支持，DALL·E 3 仅 n=1 |
| Azure DALL·E | 同步 | URL / b64_json | 同 OpenAI | standard / hd | 同 OpenAI |
| 通义万相 | 异步（内部轮询） | URL | 1024\*1024 等 | — | 支持 |
| 智谱 cogview | 同步 | URL | 按模型 | — | 支持 |
| 百度文心一格 | 异步（规划中） | URL | 按模型 | — | 支持 |
| Gemini Imagen | 同步 | Base64 内联 | imageConfig.imageSize | — | imageConfig.numberOfImages |

## 7. 测试说明

所有图像模块的单元测试均使用 JUnit 4 + `com.sun.net.httpserver.HttpServer` 在本机端口起 mock 服务，**零真实网络请求**：

- 启动临时 `HttpServer`（`127.0.0.1:0`，随机端口），baseUrl 指向该地址；
- 每个用例注册一个 `/` 上下文：记录请求 path / query / body，并按用例返回预设 JSON；
- 断言点：
  - 请求体关键字段（如 Gemini 的 `responseModalities`、OpenAI 协议的 `model`/`prompt`）；
  - 鉴权形态（`?key=` 查询参数 vs `Authorization: Bearer` 头）；
  - 响应解析结果（`firstB64()` / `firstUrl()` / data 列表长度）；
  - 错误映射（非 2xx → `AiApiException`）；
- 异步平台额外验证：mock「提交返回 task_id → 首次轮询 RUNNING → 二次轮询 SUCCEEDED」两段式响应，断言轮询间隔与结果提取。

运行（以 Gemini 模块为例）：

```bash
mvn -B -pl sure-ai-gemini -am test
```
