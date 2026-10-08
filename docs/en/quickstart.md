# Quick Start (5 minutes)

> English page · Chinese source: [../COOKBOOK.md](../COOKBOOK.md) (5-minute intro and scenario recipes).

Goal: add the dependency, send your first chat, stream a reply, and switch platforms.

Prerequisites: JDK 21+ and an API key. Keys are read from environment variables with
the unified prefix `SURE_AI_<PLATFORM>_API_KEY`, e.g. `SURE_AI_OPENAI_API_KEY`.

## 1. Import the BOM, then pick one platform module

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.tasure</groupId>
      <artifactId>sure-ai-bom</artifactId>
      <version>2.2.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
  </dependency>
</dependencies>
```

Want all 23 platforms plus RAG and Agent at once? Replace the platform dependency
above with the aggregator `sure-ai-all` (`type=pom`).

## 2. Minimal chat — one line

```java
import com.sure.ai.openai.OpenAiUtil;

// prerequisite: export SURE_AI_OPENAI_API_KEY=sk-xxx
String reply = OpenAiUtil.chat("gpt-4o-mini", "Introduce yourself in one sentence.").firstText();
System.out.println(reply);
```

The static `XxxUtil.chat(model, prompt)` returns a `ChatResponse`; `firstText()`
gives the first text segment. The key is read from the environment by default.

## 3. Streaming + a configured client

For timeouts, rate limiting and retry, build a `Client` explicitly:

```java
import java.util.List;
import com.sure.ai.client.AiConfig;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;

OpenAiClient client = new OpenAiClient(
        AiConfig.builder().apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());

// synchronous
System.out.println(client.chat(OpenAiModels.GPT_4O_MINI, "Hello").firstText());

// streaming: print incremental text chunk by chunk
client.chatStream(ChatRequest.builder()
        .model(OpenAiModels.GPT_4O_MINI)
        .messages(List.of(ChatMessage.user("Write a four-line poem.")))
        .build(),
        chunk -> {
            if (chunk.deltaText() != null) {
                System.out.print(chunk.deltaText());
            }
        });

client.close();
```

## 4. Switch platforms

Every platform module follows the same shape. Swap the dependency and the static
class — the call pattern is identical:

```java
// DeepSeek, after adding sure-ai-deepseek and exporting SURE_AI_DEEPSEEK_API_KEY
import com.sure.ai.deepseek.DeepSeekUtil;
String reply = DeepSeekUtil.chat("deepseek-chat", "Hello").firstText();
```

Local models need no key:

```java
// Ollama, after adding sure-ai-ollama
import com.sure.ai.ollama.OllamaUtil;
String reply = OllamaUtil.chat("llama3.1", "Hello").firstText();
```

## Next steps

- **Platform matrix, baseUrl and model constants**: [platforms.md](./platforms.md)
- **Structured JSON output**: [structured-output.md](./structured-output.md)
- **Multi-platform gateway routing**: [gateway.md](./gateway.md)
- **No-JVM terminal usage**: [cli.md](./cli.md)
- **All 9 copy-paste recipes** (gateway / RAG / agent / async virtual threads / observability / archetype / native): [../COOKBOOK.md](../COOKBOOK.md)
