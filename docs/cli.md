# sure-ai-cli 命令行工具

`sure-ai-cli`（应用层模块，坐标 `io.github.tasure:sure-ai-cli`）让你在终端里直接问答，
无需写一行 Java：一次性问答、流式输出、本地文档 RAG、平台切换、交互式 repl。

- 运行期零第三方依赖：仅 JDK + sure-ai-all（全部 23 个平台）。
- 可打 fat jar，**可 GraalVM native-image 编译**（复用全库 AOT 元数据）。
- CLI 属应用层：注册在父工程 `<modules>`，但**不进入 sure-ai-all 聚合链**，也不登记 BOM（与 `sure-ai-examples` 同例）。

## 1. 安装与构建

### 1.1 打 fat jar

```bash
mvn -pl sure-ai-cli -am package -DskipTests
```

产物：

```
sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar   # 可执行 fat jar（含 Main-Class）
```

运行：

```bash
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar list
```

> shade 插件已配置：`ManifestResourceTransformer` 写入主类 `com.sure.ai.cli.Main`，
> `ServicesResourceTransformer` 合并 `META-INF/services`；只剔除签名文件
> （`*.SF/*.DSA/*.RSA`），**保留各平台 jar 里的 `META-INF/native-image/**` AOT 元数据**，
> native-image 构建器会自动发现并合并。

### 1.2 编译原生可执行文件（GraalVM native-image）

前置：安装 GraalVM 21+ 并装好 `native-image`。fat jar 已内联全部 AOT 元数据，直接：

```bash
native-image \
  -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar \
  -o sureai
```

CLI 自身的 `native-image.properties` 继承全库参数：

```
Args = -H:+AddAllCharsets --enable-url-protocols=https,http
```

CLI 不加载 classpath 资源、不做 JSON 反射（`reflect-config.json` 为空 `[]`），
故无需额外 `-H:IncludeResources`。

## 2. 命令速查

```
sureai [全局选项] <子命令> [子命令选项] [问题]
```

### 全局选项（出现在子命令之前）

| 选项 | 说明 | 默认 |
|---|---|---|
| `--provider <平台>` | 平台名，见 `sureai list` | `openai` |
| `--api-key <key>` | 显式传 Key；缺省回退环境变量 | — |
| `--model <模型>` | 覆盖默认模型 | 各平台默认 |
| `--base-url <url>` | 覆盖网关地址 | 各平台默认 |

### 子命令

| 子命令 | 作用 | 示例 |
|---|---|---|
| `chat "问题"` | 一次性同步问答，打印完整回答 | `sureai chat "用一句话解释 RAG"` |
| `stream "问题"` | 流式逐片打印增量文本 | `sureai stream "写一首短诗"` |
| `rag "问题" --doc <文件>` | 本地纯文本文档 RAG 问答 | `sureai rag "公司成立于哪年？" --doc ./company.txt` |
| `list` | 列出全部平台与默认模型 | `sureai list` |
| `repl` | 交互式多轮会话（`exit`/`quit` 退出） | `sureai --provider deepseek repl` |

RAG 专属选项：

- `--doc <路径>`：本地 UTF-8 纯文本文件（必填）。
- `--embedding-model <模型>`：覆盖默认嵌入模型；不支持嵌入的平台需显式指定。

RAG 流程：读取本地文档 → 递归字符分块 → 进程内向量库（`InMemoryVectorStore`）入库 →
检索 topK=4 → 把命中文档拼入系统提示词 → 调用对话模型生成。**全程不发起文档网络下载。**

## 3. 平台切换示例

```bash
# OpenAI（默认）
export SURE_AI_OPENAI_API_KEY=sk-xxx
sureai chat "你好"

# 切换 DeepSeek
export SURE_AI_DEEPSEEK_API_KEY=sk-xxx
sureai --provider deepseek stream "讲个笑话"

# 切换通义千问并指定模型
sureai --provider qwen --model qwen-max chat "写段 Python 快排"

# 本地 Ollama（无需 Key）
sureai --provider ollama --model llama3.1 --base-url http://localhost:11434 chat "hello"
```

未配置 Key 的平台会报清晰错误，例如：

```
平台 openai 未配置 API Key：请用 --api-key 传入，或设置环境变量 SURE_AI_OPENAI_API_KEY
```

## 4. 环境变量表

凭证优先级：`--api-key` 命令行 > 平台对应环境变量。

| 平台 | 环境变量 | 默认模型 | 支持嵌入 |
|---|---|---|---|
| openai | `SURE_AI_OPENAI_API_KEY` | gpt-4o-mini | ✅ text-embedding-3-small |
| azure | `SURE_AI_AZURE_API_KEY` | gpt-4o | — |
| anthropic | `SURE_AI_ANTHROPIC_API_KEY` | claude-3-5-sonnet-latest | — |
| gemini | `SURE_AI_GEMINI_API_KEY` | gemini-1.5-pro | — |
| deepseek | `SURE_AI_DEEPSEEK_API_KEY` | deepseek-chat | — |
| qwen | `SURE_AI_QWEN_API_KEY` | qwen-plus | ✅ text-embedding-v3 |
| zhipu | `SURE_AI_ZHIPU_API_KEY` | glm-4 | ✅ embedding-3 |
| moonshot | `SURE_AI_MOONSHOT_API_KEY` | moonshot-v1-8k | — |
| doubao | `SURE_AI_DOUBAO_API_KEY` | doubao-pro-32k | — |
| baidu | `SURE_AI_BAIDU_API_KEY` | ernie-4.0-8k | — |
| ollama | （本地，无需 Key） | llama3.1 | — |
| grok | `SURE_AI_GROK_API_KEY` | grok-2 | — |
| mistral | `SURE_AI_MISTRAL_API_KEY` | mistral-small-latest | — |
| llamacpp | （本地，无需 Key） | local-model | — |
| cohere | `SURE_AI_COHERE_API_KEY` | command-r-plus | — |
| bedrock | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_REGION` | anthropic.claude-3-5-sonnet | — |
| minimax | `SURE_AI_MINIMAX_API_KEY` | abab6.5s-chat | — |
| stepfun | `SURE_AI_STEPFUN_API_KEY` | step-2-16k | — |
| baichuan | `SURE_AI_BAICHUAN_API_KEY` | Baichuan4 | — |
| lingyi | `SURE_AI_LINGYI_API_KEY` | yi-large | — |
| siliconflow | `SURE_AI_SILICONFLOW_API_KEY` | Qwen/Qwen2.5-7B-Instruct | ✅ bge-small-zh-v1.5 |
| hunyuan | `SURE_AI_HUNYUAN_API_KEY` | hunyuan-pro | — |
| spark | `SURE_AI_SPARK_API_KEY` | generalv3.5 | — |

共 23 个平台。RAG 需选择「支持嵌入」的平台，或用 `--embedding-model` 显式指定。

## 5. 退出码

| 退出码 | 含义 |
|---|---|
| `0` | 成功 |
| `1` | 用户输入错误：缺参数、未知平台/子命令、未配置凭证、文档不存在等 |
| `2` | 调用平台 API 时异常：网络、鉴权、限流、服务端错误等 |

## 6. 架构与可测试性

```
Main（薄壳：装配默认依赖 + System.exit）
  └─ CliRunner（纯逻辑：解析 → 装配 → 执行；输出写注入 PrintStream）
       ├─ ArgsParser   手写参数解析（零第三方，不引 picocli/jline）
       ├─ ClientFactory 客户端装配接缝（测试注入 FakeAiClient）
       │    └─ DefaultClientFactory → ProviderRegistry（23 平台静态表）
       └─ rag：RagUtil.pipeline + InMemoryVectorStore
```

测试零真实网络：`FakeAiClient` 同时实现 `AiClient` 与 `EmbeddingClient`，
chat 返回固定文本、chatStream 逐片推送、embed 返回字符哈希向量；
stdout 通过 `ByteArrayOutputStream` 捕获断言。
