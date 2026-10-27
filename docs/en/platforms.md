# Platform Matrix

> English page · Chinese source: [../capabilities.md](../capabilities.md) (capability guard) and the platform table in [README.en.md](../../README.en.md).

sureai ships **23 platforms**. Each is a separate Maven module depending only on
`sure-ai-core`, so you pull exactly what you use.

## Capability guard (fail-fast)

Since v1.4.0 each client declares the set of capabilities it actually supports via
`AbstractAiClient#capabilities()`. Calling an unsupported capability — e.g.
`embed()` on DeepSeek — throws **before any HTTP request is sent**:

```text
deepseek does not support EMBED capability
```

`CHAT` and `CHAT_STREAM` are core and never guarded; the guarded capabilities are
`EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE`. The full per-platform declared
set is in [../capabilities.md](../capabilities.md).

## Platform table

| Platform slug | artifactId | Default baseUrl | Auth | Embedding |
|---|---|---|---|---|
| `openai` | `sure-ai-openai` | `https://api.openai.com/v1` | Bearer | ✅ |
| `azure` | `sure-ai-azure` | `https://{resource}.openai.azure.com` | api-key header | ✅ |
| `anthropic` | `sure-ai-anthropic` | `https://api.anthropic.com/v1` | x-api-key | ❌ |
| `gemini` | `sure-ai-gemini` | `https://generativelanguage.googleapis.com/v1beta` | `?key=` | ✅ |
| `deepseek` | `sure-ai-deepseek` | `https://api.deepseek.com` | Bearer | ❌ |
| `qwen` | `sure-ai-qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Bearer | ✅ |
| `zhipu` | `sure-ai-zhipu` | `https://open.bigmodel.cn/api/paas/v4` | JWT HS256 | ✅ |
| `moonshot` | `sure-ai-moonshot` | `https://api.moonshot.cn/v1` | Bearer | ✅ |
| `doubao` | `sure-ai-doubao` | `https://ark.cn-beijing.volces.com/api/v3` | Bearer | ✅ |
| `baidu` | `sure-ai-baidu` | `https://aip.baidubce.com` | access_token (cached) | ✅ |
| `ollama` | `sure-ai-ollama` | `http://localhost:11434` | none (local) | ✅ |
| `grok` | `sure-ai-grok` | `https://api.x.ai/v1` | Bearer | ❌ |
| `mistral` | `sure-ai-mistral` | `https://api.mistral.ai/v1` | Bearer | ✅ |
| `cohere` | `sure-ai-cohere` | `https://api.cohere.com/v2` | Bearer | ✅ |
| `llamacpp` | `sure-ai-llamacpp` | `http://localhost:8080/v1` | optional Bearer | ✅ |
| `bedrock` | `sure-ai-bedrock` | `https://bedrock-runtime.{region}.amazonaws.com` | SigV4 (AK/SK) | ❌ |
| `minimax` | `sure-ai-minimax` | `https://api.minimax.cn/v1` | Bearer | ❌ |
| `stepfun` | `sure-ai-stepfun` | `https://api.stepfun.com/v1` | Bearer | ❌ |
| `baichuan` | `sure-ai-baichuan` | `https://api.baichuan-ai.com/v1` | Bearer | ❌ |
| `lingyi` | `sure-ai-lingyi` | `https://api.lingyiwanwu.com/v1` | Bearer | ❌ |
| `siliconflow` | `sure-ai-siliconflow` | `https://api.siliconflow.cn/v1` | Bearer | ✅ |
| `hunyuan` | `sure-ai-hunyuan` | `https://api.hunyuan.cloud.tencent.com/v1` | Bearer | ✅ |
| `spark` | `sure-ai-spark` | `https://spark-api-open.xf-yun.com/v1` | Bearer | ❌ |

> The full capability columns (image / video / TTS / STT / rerank / structured /
> multimodal / batches / realtime / reasoning / grounding / fine-tune / moderation /
> model listing) are in the platform table of [README.en.md](../../README.en.md).

## Model constants

Each platform module exposes a `*Models` constants class so you do not hard-code
model IDs in two places. Example from OpenAI:

```java
import com.sure.ai.openai.OpenAiModels;

client.chat(OpenAiModels.GPT_4O_MINI, "Hello");            // "gpt-4o-mini"
String emb = OpenAiModels.TEXT_EMBEDDING_3_SMALL;          // "text-embedding-3-small"
```

Every platform's default model and its `SURE_AI_<PLATFORM>_API_KEY` variable are
listed in [cli.md](./cli.md) and in the Environment Variables section of
[README.en.md](../../README.en.md).

## Next steps

- [Quick Start](./quickstart.md) · [Gateway routing](./gateway.md) · [RAG](./rag.md)
