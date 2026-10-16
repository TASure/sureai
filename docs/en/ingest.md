# Document Ingest

> English page · Chinese source: [../ingest.md](../ingest.md). Module: `sure-ai-ingest`.

Since **v2.6.0**, sureai ships `sure-ai-ingest` — a data-import layer modeled on
LangChain4j's document loaders. It reads local files / URLs into metadata-bearing
RAG `Document`s, routing each file to the right parser by extension / Content-Type.
Zero third-party runtime dependencies; included in `sure-ai-all` / `sure-ai-bom`.

## Responsibility boundary

The loader only does **source → plain text + metadata**. Splitting is done by rag's
`TextSplitter`; embedding + storage by `RagPipeline` — a clean three-stage chain:

```
source (Path / URI) → DocumentLoader.load() → List<Document>
  → TextSplitter.split() → chunks → RagPipeline.ingest() → VectorStore → search
```

Failures (missing file, read error, non-2xx HTTP, unsupported format) throw
`IllegalStateException`; empty files / PDFs with no text layer return an **empty list**.

## Quick start

```java
import com.sure.ai.ingest.FileSystemLoader;

FileSystemLoader loader = FileSystemLoader.createDefault();   // TXT/MD/HTML/PDF built in
List<Document> docs = loader.load(Path.of("docs/intro.md"));
```

`URLLoader.builder().requestTimeout(Duration.ofSeconds(30)).build()` handles
`http(s)` URLs (JDK `HttpClient`; extension falls back to the `Content-Type` header).

## Format support (honest limits)

| Format | Parser | Notes |
| --- | --- | --- |
| TXT / Markdown | `PlainTextParser` | UTF-8; Markdown is read **as plain text** (no marker stripping) — structure splitting is left to rag's `MarkdownTextSplitter` |
| HTML | `HtmlTextParser` | minimal regex strip (`<script>`/`<style>` removed, entities decoded); simple static HTML only, no JS rendering |
| PDF | `PdfTextParser` | **pure-JDK finite text-layer extraction** (no PDFBox): standard single-byte-font `Tj`/`TJ` only. No font/ToUnicode mapping, no scanned/image PDFs, no encrypted PDFs, no multi-column reading order; unsupported PDFs yield an empty list |
| DOCX / XLSX / PPTX | POI module | optional `sure-ai-ingest-poi`; Apache POI 5.5.1 is `provided` — you must declare `poi-ooxml` yourself |

```java
FileSystemLoader loader = FileSystemLoader.builder()
        .register(PoiParsers.docx())
        .register(PoiParsers.xlsx())
        .register(PoiParsers.pptx())
        .build();
```

Custom formats implement `com.sure.ai.ingest.spi.DocumentParser`
(`parse(byte[], source, metadata)` + `extensions()`) and are registered via
`Builder.register(...)`.

## End-to-end chain

```java
List<Document> files = FileSystemLoader.createDefault().load(Path.of("docs/guide.md"));
RagPipeline pipeline = RagUtil.pipeline(client, client,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);
for (Document f : files) {
    int chunks = pipeline.ingest(f);          // splits → embeds → writes to the store
}
List<Document> hits = pipeline.retrieve("how do I plug in an external store?", 4);
```

Retrieval on the pipeline is `retrieve(query, topK)` / `retrieveWithScores(query, topK)`;
`VectorStore.similaritySearch(...)` is the lower-level, per-store entry point.

## Next steps

- [RAG pipeline (en)](./rag.md) · [Vector stores (zh)](../vector-stores.md) ·
  [Cookbook scenario 18 (zh)](../COOKBOOK.md)
