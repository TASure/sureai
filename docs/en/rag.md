# RAG — Retrieval-Augmented Generation

> English page · Chinese source: [../rag.md](../rag.md). Module: `sure-ai-rag`.

sureai gives you an end-to-end RAG pipeline in one line: split text → embed → store →
retrieve → augment → generate. It is decoupled from any platform — combine any
platform's chat client with any embedding client.

## The pipeline

```
document → TextSplitter chunk → EmbeddingProvider → VectorStore
question → embed → similarity search → context assembly → AiClient → answer
```

`RagUtil.pipeline(chatClient, embeddingClient, chatModel, embeddingModel)` wires the
default together: in-memory vector store (cosine similarity) + recursive-character
splitting, default topK=4.

## Minimal example

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.pipeline.RagPipeline;

OpenAiClient client = new OpenAiClient(
        AiConfig.builder().apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());

// chat and embedding reuse one client (OpenAI implements both AiClient and EmbeddingClient)
RagPipeline pipeline = RagUtil.pipeline(client, client,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);

// ingest: auto chunk → embed → write to the in-memory store
int chunks = pipeline.ingest("sureai-intro",
        "sureai is a zero-dependency Java LLM toolkit with chat, Function Calling and RAG.");

// retrieve + generate: auto-recall topK context into the system prompt
var answer = pipeline.ask("What does sureai support?", 3);
System.out.println(answer.firstText());

client.close();
```

## Customize with the builder

```java
RagPipeline pipeline = RagPipeline.builder()
        .chatClient(chatClient)
        .chatModel("gpt-4o-mini")
        .embeddingClient(embedClient)
        .embeddingModel("text-embedding-3-small")
        .splitter(new RecursiveCharacterTextSplitter(800, 160, null, true))
        .vectorStore(new InMemoryVectorStore())   // swap in an external store
        .defaultTopK(6)
        .minScore(0.5)
        .systemPromptTemplate("Answer using the context:\n{context}")
        .build();
```

## In-memory vs external vector stores

Out of the box you get `InMemoryVectorStore`. To use Milvus, Qdrant, pgvector,
Elasticsearch, Redis, etc., implement the `VectorStore` interface (`add` / `addAll`
/ `delete` / `clear` / `size` / `similaritySearch` with a threshold overload) and
pass it to `.vectorStore(...)`. Built-in adapters ship for Milvus / Chroma /
Qdrant / Pinecone / Weaviate / Elasticsearch / OpenSearch / Redis — see
[../vector-stores.md](../vector-stores.md).

## What else is in the box

The long-form Chinese page documents the rest; highlights:

- **Splitters**: recursive-character, Markdown-heading, fixed-size, semantic (embedding-based), parent-child (small-to-big).
- **Retrievers**: vector (`VectorRetriever`), BM25 keyword (`KeywordRetriever`), weighted hybrid (`HybridRetriever`), plus LLM-assisted strategies — HyDE, Multi-Query (RRF), CRAG corrective, parent-child, multimodal.
- **GraphRAG**: entity-relation extraction → community detection → per-community summaries.
- **RAG evaluation**: LLM-as-judge metrics (`faithfulness`, `context_precision`, `context_recall`, `answer_relevancy`) with threshold assertions and trace replay.

All LLM-dependent strategies **degrade instead of throwing** when the model call fails, so retrieval keeps working.

## Next steps

- [Quick Start](./quickstart.md) · [Agent](./agent.md) · [CLI RAG](./cli.md)
