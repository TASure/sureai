# sureai

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21+-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven Central](https://img.shields.io/badge/maven--central-0.1.0-lightgrey.svg)](https://central.sonatype.com/)
[![CI](https://img.shields.io/badge/CI-passing-brightgreen.svg)](.github/workflows/ci.yml)

**零第三方依赖的 Java 大模型接入工具基础设施。** 每个主流 AI 平台一个独立模块与静态入口工具类，模块间互相隔离，按需引入。

## 与同类框架的差异化

| 维度 | sureai | langchain4j | Spring AI |
|------|--------|-------------|-----------|
| 第三方依赖 | **零**（仅 JDK + 自研 JSON/HTTP） | 传递依赖链庞大 | 绑定 Spring 生态 |
| 开箱即用 | **静态工具类一行调用** | 需 Builder 装配 | 需 @Configuration + Bean |
| 国产平台覆盖 | **11 个平台全覆盖**（含百度、智谱、豆包等） | 部分覆盖 | 部分覆盖 |
| 模块隔离 | **模块级零依赖**，只引入需要的平台 | 整体引入 | 整体引入 |
| JDK 要求 | 21+（record/pattern matching） | 17+ | 17+ |

## 特性

- **零第三方运行期依赖**：内置轻量 JSON 解析与 HTTP 客户端，不引入 OkHttp/Jackson/Netty
- **静态工具类开箱即用**：`OpenAiUtil.chat(model, prompt)` 一行完成对话
- **模块级隔离**：只引入需要的平台模块，不引入无关依赖
- **国产平台全覆盖**：OpenAI / Azure / Anthropic / Gemini / DeepSeek / 通义千问 / 智谱 / Moonshot / 豆包 / 百度千帆 / Ollama
- **流式调用**：统一 SSE 流式接口，逐片回调
- **Function Calling**：工具声明与调用闭环
- **Embedding**：向量生成（支持平台见下表）
- **环境变量自动配置**：未显式 init 时自动从 `SURE_AI_*` 环境变量读取
- **JDK 21**：record / pattern matching / switch 模式

## 模块与平台一览

| 平台 | artifactId | 默认 baseUrl | 鉴权方式 | 流式 | Embedding | Function Calling |
|------|-----------|-------------|---------|------|-----------|-----------------|
| OpenAI | `sure-ai-openai` | `https://api.openai.com/v1` | Bearer | ✅ | ✅ | ✅ |
| Azure OpenAI | `sure-ai-azure` | `https://{resource}.openai.azure.com` | api-key 头 | ✅ | ✅ | ✅ |
| Anthropic | `sure-ai-anthropic` | `https://api.anthropic.com/v1` | x-api-key 头 | ✅ | ❌ | ✅ |
| Google Gemini | `sure-ai-gemini` | `https://generativelanguage.googleapis.com/v1beta` | ?key= 查询参数 | ✅ | ✅ | ✅ |
| DeepSeek | `sure-ai-deepseek` | `https://api.deepseek.com` | Bearer | ✅ | ❌ | ✅ |
| 通义千问 | `sure-ai-qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Bearer | ✅ | ✅ | ✅ |
| 智谱 GLM | `sure-ai-zhipu` | `https://open.bigmodel.cn/api/paas/v4` | JWT (HS256) | ✅ | ✅ | ✅ |
| Moonshot | `sure-ai-moonshot` | `https://api.moonshot.cn/v1` | Bearer | ✅ | ✅ | ✅ |
| 豆包 | `sure-ai-doubao` | `https://ark.cn-beijing.volces.com/api/v3` | Bearer | ✅ | ✅ | ✅ |
| 百度千帆 | `sure-ai-baidu` | `https://aip.baidubce.com` | access_token（自动缓存） | ✅ | ✅ | ✅ |
| Ollama | `sure-ai-ollama` | `http://localhost:11434` | 无（本地服务） | ✅ | ✅ | ✅ |

聚合模块：`sure-ai-all`（一个依赖引入全部平台）、`sure-ai-bom`（版本统一管理）。

## 快速开始

```java
import com.sure.ai.openai.OpenAiUtil;

// 1. 设置环境变量 SURE_AI_OPENAI_API_KEY
// 2. 一行调用
String reply = OpenAiUtil.chat("gpt-4o-mini", "你好！").firstText();
System.out.println(reply);
```

## Maven 依赖

按需引入单个平台模块：

```xml
<!-- OpenAI -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Azure OpenAI -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-azure</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Anthropic Claude -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-anthropic</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Google Gemini -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-gemini</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- DeepSeek -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-deepseek</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- 通义千问 DashScope -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-qwen</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- 智谱 GLM -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-zhipu</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Moonshot Kimi -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-moonshot</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- 火山引擎豆包 -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-doubao</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- 百度千帆 -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-baidu</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Ollama 本地模型 -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-ollama</artifactId>
    <version>0.1.0</version>
</dependency>
```

引入全部平台：

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-all</artifactId>
    <version>0.1.0</version>
    <type>pom</type>
</dependency>
```

## 流式调用

```java
OpenAiUtil.chatStream(
    ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("用一句话介绍 Java。")))
        .build(),
    chunk -> {
        if (chunk.deltaText() != null) {
            System.out.print(chunk.deltaText());
        }
    }
);
```

## Function Calling

```java
ToolFunction weatherFn = ToolFunction.of(
    "getWeather",
    "查询指定城市的当前天气",
    "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
);

ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("上海今天天气怎么样？")))
    .tools(List.of(ToolSpec.of(weatherFn)))
    .build();

ChatResponse resp = OpenAiUtil.chat(req);
List<ToolCall> calls = resp.choices().get(0).message().toolCalls();
// 执行工具后将结果作为 ChatMessage.tool(toolCallId, result) 追加，再次请求
```

## Embedding

```java
EmbeddingResponse resp = OpenAiUtil.embed("text-embedding-3-small", "测试文本");
float[] vector = resp.embeddings().get(0);
System.out.println("向量维度: " + vector.length);
```

## 环境变量配置

| 平台 | 环境变量 | 必填 | 说明 |
|------|---------|------|------|
| OpenAI | `SURE_AI_OPENAI_API_KEY` | ✅ | API Key |
| | `SURE_AI_OPENAI_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Azure | `SURE_AI_AZURE_API_KEY` | ✅ | API Key |
| | `SURE_AI_AZURE_RESOURCE` | ❌ | Azure 资源名 |
| | `SURE_AI_AZURE_BASE_URL` | ❌ | 完整 baseUrl |
| Anthropic | `SURE_AI_ANTHROPIC_API_KEY` | ✅ | API Key |
| | `SURE_AI_ANTHROPIC_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Gemini | `SURE_AI_GEMINI_API_KEY` | ✅ | API Key |
| | `SURE_AI_GEMINI_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| DeepSeek | `SURE_AI_DEEPSEEK_API_KEY` | ✅ | API Key |
| | `SURE_AI_DEEPSEEK_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 通义千问 | `SURE_AI_QWEN_API_KEY` | ✅ | DashScope API Key |
| | `SURE_AI_QWEN_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 智谱 | `SURE_AI_ZHIPU_API_KEY` | ✅ | `id.secret` 格式 |
| | `SURE_AI_ZHIPU_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Moonshot | `SURE_AI_MOONSHOT_API_KEY` | ✅ | API Key |
| | `SURE_AI_MOONSHOT_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 豆包 | `SURE_AI_DOUBAO_API_KEY` | ✅ | Ark API Key |
| | `SURE_AI_DOUBAO_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| 百度千帆 | `SURE_AI_BAIDU_API_KEY` | ✅ | 千帆 API Key |
| | `SURE_AI_BAIDU_SECRET_KEY` | ✅ | 千帆 Secret Key |
| | `SURE_AI_BAIDU_BASE_URL` | ❌ | 覆盖默认 baseUrl |
| Ollama | `SURE_AI_OLLAMA_BASE_URL` | ❌ | 覆盖 `localhost:11434` |

## 架构与隔离设计

sureai 采用严格的模块级隔离架构：

```
sure-ai-core          ← 公共模型/接口/HTTP/JSON（所有平台依赖此模块）
  ├── sure-ai-openai
  ├── sure-ai-azure
  ├── sure-ai-anthropic
  ├── sure-ai-gemini
  ├── sure-ai-deepseek
  ├── sure-ai-qwen
  ├── sure-ai-zhipu
  ├── sure-ai-moonshot
  ├── sure-ai-doubao
  ├── sure-ai-baidu
  └── sure-ai-ollama
sure-ai-bom           ← 版本统一管理（BOM）
sure-ai-all           ← 聚合引入全部平台
sure-ai-examples      ← 使用示例
```

**核心设计原则：**

- 每个平台模块只依赖 `sure-ai-core`，平台间**零依赖**
- 核心模块内置自研 JSON 解析器与 SSE 行读取器，不引入第三方库
- 静态工具类双检锁懒加载，未初始化时从环境变量自动读取
- 各平台特有鉴权逻辑（JWT / access_token 缓存 / 自定义请求头）封装在各自模块内

## 构建指南

```bash
# 需要 JDK 21+ 和 Maven 3.9+
mvn -B clean verify
```

此命令执行：编译 → 单元测试 → checkstyle → spotbugs → jacoco 覆盖率门禁 → license 头校验。

## 贡献指南

欢迎提交 Issue 和 PR！详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## License

[Apache License 2.0](LICENSE) © sureai contributors
