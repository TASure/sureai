# 文档导入器（Document Ingest）

> 自 **v2.6.0** 起，sureai 新增 `sure-ai-ingest` 模块，对标 LangChain4j 的数据导入
> （`DocumentLoader` / 文档解析）：把「本地目录 / 网络 URL」一站式读取为带元数据的
> [rag `Document`](rag.md) 列表，按扩展名 / Content-Type 自动路由到对应格式的解析器。
> 模块仅依赖 sure-core + sure-ai-rag，**运行期零第三方依赖**；已进 `sure-ai-all` /
> `sure-ai-bom` 聚合链。

## 定位与职责边界

导入器只做一件事：**来源（本地文件 / 网络 URL）→ 纯文本 + 元数据**。它不负责分块，
也不负责向量化与入库——分块由 rag 的 `TextSplitter` 完成，入库与检索由 `RagPipeline`
负责。三者构成清晰的三段式链路：

```
来源 (Path / URI)
   │  DocumentLoader.load(...)          ← 本模块：加载 + 按格式解析
   ▼
List<Document>（id=source，metadata: source/loaded_at/format[/content_type]）
   │  TextSplitter.split(...)            ← rag：切分为适合向量化的块
   ▼
List<Document> chunks
   │  RagPipeline.ingest(...)            ← rag：向量化 + 写入 VectorStore
   ▼
相似度检索 / 增强问答
```

- 加载失败（文件不存在、读取错误、HTTP 非 2xx、不支持的格式）抛出 `IllegalStateException`。
- 空文件 / 无文本层（如扫描件 PDF）返回**空列表**，而非抛异常——避免中断批量入库管线。
- 返回列表不含 `null`；每条文档至少含 `source` 元数据。

## 快速开始

一行加载本地 TXT / Markdown：

```java
import com.sure.ai.ingest.FileSystemLoader;
import com.sure.ai.rag.model.Document;

FileSystemLoader loader = FileSystemLoader.createDefault();   // 内置 TXT/MD/HTML/PDF

List<Document> docs = loader.load(Path.of("docs/intro.md"));
for (Document d : docs) {
    System.out.println(d.id());                 // 来源路径
    System.out.println(d.metadata().get("format"));   // md / txt / html / pdf
}
```

> 默认加载器已注册 TXT / MD / HTML / PDF 四个解析器；DOCX / XLSX / PPTX 需配合可选模块
> `sure-ai-ingest-poi`（见下文「Office 文档（可选 POI）」）。

## 加载器详解

### `DocumentLoader` SPI（`com.sure.ai.ingest`）

```java
public interface DocumentLoader {
    List<Document> load(Path path);   // 加载本地路径
    List<Document> load(URI uri);      // 加载网络 / 任意 URI
}
```

统一写入的元数据键（常量）：

| 常量 | 键 | 含义 |
| --- | --- | --- |
| `META_SOURCE` | `source` | 来源标识（路径或 URL），同时作为文档 `id` |
| `META_LOADED_AT` | `loaded_at` | 加载时刻的 ISO-8601 时间戳 |
| `META_FORMAT` | `format` | 格式标签（`txt`/`md`/`html`/`pdf`/`docx`…） |
| `META_CONTENT_TYPE` | `content_type` | HTTP 响应 Content-Type（仅 URL 来源） |

### `FileSystemLoader`（本地文件）

按**扩展名**路由到对应 `DocumentParser`。`load(URI)` 仅支持 `file:` scheme（转路径读取）；
网络 URL 请用 `URLLoader`。

```java
FileSystemLoader loader = FileSystemLoader.builder()
        .register(/* 追加自定义 / POI 解析器 */)
        .build();
```

- `FileSystemLoader.createDefault()`：含内置解析器的默认加载器。
- `FileSystemLoader.builder()` → `Builder.register(DocumentParser)` 追加解析器 → `build()`。

### `URLLoader`（网络 URL）

基于 JDK `HttpClient` GET 下载字节后路由。仅支持 `http` / `https` scheme：

```java
import java.time.Duration;
import com.sure.ai.ingest.URLLoader;

URLLoader loader = URLLoader.builder()
        .requestTimeout(Duration.ofSeconds(30))   // 默认 30s
        .build();

List<Document> pages = loader.load(URI.create("https://example.com/guide.pdf"));
```

- 默认连接超时 10s、请求超时 30s，跟随重定向（`Redirect.NORMAL`）。
- **Content-Type 兜底**：先按 URI 路径末段扩展名选解析器；无扩展名时按响应 `Content-Type`
  推断（含 `html`→`.html`、`pdf`→`.pdf`、`markdown`→`.md`、`text/plain`→`.txt`）。
- 非 2xx 状态码抛 `IllegalStateException`（含状态码）；`Builder.httpClient(HttpClient)`
  可注入自定义客户端（便于测试 / 覆盖连接池与超时）。

## 格式支持与限制（如实标注）

| 格式 | 解析器 | 扩展名 | 支持程度 |
| --- | --- | --- | --- |
| 纯文本 | `PlainTextParser` | `.txt` | UTF-8 直读为单条文档 |
| Markdown | `PlainTextParser` | `.md` / `.markdown` | **按纯文本直读，不剥离标记符号**——保留标题/列表/代码块原始文本，交由 rag 的 `MarkdownTextSplitter` 做结构化切分 |
| HTML | `HtmlTextParser` | `.html` / `.htm` / `.xhtml` | JDK 手写正则最小实现（见下） |
| PDF | `PdfTextParser` | `.pdf` | **纯 JDK 有限文本层提取**（见下），不引入 PDFBox |
| DOCX | `DocxDocumentParser`（POI 模块） | `.docx` | 可选，需自行声明 POI 依赖 |
| XLSX | `XlsxDocumentParser`（POI 模块） | `.xlsx` | 可选 |
| PPTX | `PptxDocumentParser`（POI 模块） | `.pptx` | 可选 |

### PDF（`PdfTextParser`）——有限文本层提取

纯 JDK 手写，**不引入 PDFBox**：扫描所有 `stream ... endstream` 块，`Inflater`
（zlib / 裸 deflate 两种）解压 FlateDecode 流后，在内容流中扫描文本显示操作符
`Tj` / `TJ` 对应的字面串 `(...)` 与十六进制串 `<...>`，还原转义与八进制转义后拼接文本。

- **支持子集**：仅提取以标准单字节字体写入、顺序排布的 `(text) Tj` 与 `[...] TJ`。
- **明确不处理**：① 字体编码 / ToUnicode CMap / CID 字体映射（非 ASCII 文字可能乱码或缺失）；
  ② 扫描件 / 纯图片 PDF（无文本层，返回空）；③ 加密 PDF；④ 多栏、旋转、绝对坐标的阅读顺序；
  ⑤ 不解析 xref/trailer 做对象导航（采用全流扫描的宽容策略）。
- 损坏或不支持的 PDF **不抛异常**，按「无文本层」返回空列表。

> 如需完整 PDF 解析（表格、多栏、字体映射、扫描件 OCR），请在你的应用工程引入 PDFBox /
> Apache Tika，并实现下方 `DocumentParser` SPI 自行接入。

### HTML（`HtmlTextParser`）——最小剥离

依次：移除 `<script>...</script>` 与 `<style>...</style>` 块（含内容）→ 剥离所有 HTML 标签
→ 解码常用实体（`&nbsp;`/`&lt;`/`&amp;`…）与数字实体 → 合并空白。
JDK 21 无内置 HTML DOM 解析器，本实现基于正则：**不处理复杂嵌套、畸形标签、JS 渲染页面与
CSS 布局还原，仅适合简单静态 HTML。**

### Office 文档（可选 POI 模块 `sure-ai-ingest-poi`）

DOCX / XLSX / PPTX 由 Apache POI 解析，封装在可选模块中。**Apache POI 以 `provided` 引入，
不向下游传递、不进 `sure-ai-all` 运行期聚合链**（保持主库运行期零第三方依赖）；使用方需在
自己的工程中显式声明 `org.apache.poi:poi-ooxml` 依赖（统一版本 **5.5.1**）：

```xml
<dependency>
  <groupId>io.github.tasure</groupId>
  <artifactId>sure-ai-ingest-poi</artifactId>
</dependency>
<!-- POI 为 provided，必须自行显式声明，否则运行期 NoClassDefFoundError -->
<dependency>
  <groupId>org.apache.poi</groupId>
  <artifactId>poi-ooxml</artifactId>
  <version>5.5.1</version>
</dependency>
```

便捷工厂 `PoiParsers` 一次性产出三个解析器，注册进加载器：

```java
import com.sure.ai.ingest.FileSystemLoader;
import com.sure.ai.ingest.poi.PoiParsers;

FileSystemLoader loader = FileSystemLoader.builder()
        .register(PoiParsers.docx())
        .register(PoiParsers.xlsx())
        .register(PoiParsers.pptx())
        // .register(PoiParsers.all().get(0)) // 或用 all() 批量
        .build();

List<Document> docs = loader.load(Path.of("docs/report.docx"));
```

## 扩展点：自定义格式（`DocumentParser` SPI）

新增一种格式，实现 `com.sure.ai.ingest.spi.DocumentParser` 并 `register` 进加载器即可：

```java
public interface DocumentParser {
    List<Document> parse(byte[] content, String source, Map<String, String> metadata);
    Set<String> extensions();   // 小写、含前导点，如 {".epub"}
}
```

解析器无状态、可并发复用；空内容 / 无文本层返回空列表；仅确属致命错误时抛
`IllegalStateException`。`metadata` 已含 `source`/`loaded_at`（URL 来源另含 `content_type`），
解析器可追加格式专属键（如 `format`）。

## 完整链路示例：加载 → 分块 → 入库 → 检索

```java
import com.sure.ai.ingest.FileSystemLoader;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.splitter.MarkdownTextSplitter;
import com.sure.ai.openai.OpenAiModels;

// 1) 加载：本地 Markdown → 单条 Document（metadata 含 source/loaded_at/format=md）
FileSystemLoader loader = FileSystemLoader.createDefault();
List<Document> files = loader.load(Path.of("docs/guide.md"));

// 2) 装配 RAG 管线：对话与向量化复用同一 OpenAI client
RagPipeline pipeline = RagUtil.pipeline(client, client,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);

// 3) 入库：pipeline 内部用 splitter 把文档正文切块 → 向量化 → 写入 VectorStore
for (Document file : files) {
    int chunks = pipeline.ingest(file);          // 复用文档 id 与元数据，返回切块数
}

// 4) 检索：召回 topK 相关块（不含得分），或 retrieveWithScores 含相似度
List<Document> hits = pipeline.retrieve("如何接入外部向量库？", 4);
System.out.println("命中文档数：" + hits.size());
```

> **检索路径说明**：`RagPipeline` 对外暴露的检索入口是 `retrieve(query, topK)`（返回
> `List<Document>`）与 `retrieveWithScores(query, topK)`（含相似度得分）；底层
> `VectorStore.similaritySearch(queryVec, topK)` 是向量库级别的低层接口，一般不直接调用。
> 若需在管线外直接操作某个向量库，可参考 [vector-stores.md](vector-stores.md) 的
> `store.similaritySearch(...)` 用法。

- **分块器可换**：`RagPipeline.builder().splitter(new MarkdownTextSplitter(800, 100))`
  覆盖默认的递归字符分块（Markdown 源文件推荐 `MarkdownTextSplitter`）。
- **入库两种重载**：`pipeline.ingest(String sourceId, String text)`（先切块再入库）与
  `pipeline.ingest(Document doc)`（保留文档元数据，按 `doc.id()#i` 命名分块）。

## 与 rag 既有加载器的关系

`sure-ai-rag` 早期内置了单格式加载器 `com.sure.ai.rag.loader.TxtDocumentLoader` /
`UrlDocumentLoader`（无参 `load()`，见 [rag.md「文档加载器」](rag.md#文档加载器)）。
v2.6.0 的 `com.sure.ai.ingest.DocumentLoader` 是其**多格式演进**：

| | rag 既有 `rag.loader.*` | 新 `com.sure.ai.ingest.*` |
| --- | --- | --- |
| 入参 | 构造时固定来源，`load()` 无参 | `load(Path)` / `load(URI)` 以来源为入参 |
| 格式 | TXT / URL(HTML) 两种 | 按扩展名路由 TXT/MD/HTML/PDF（+ 可选 Office） |
| 扩展点 | 无 | `DocumentParser` SPI 可插拔 |
| 适用 | 简单单文件快速试验 | 目录混合格式一站式导入 |

两者都产出同一个 `com.sure.ai.rag.model.Document` record，可直接进入同一个
`RagPipeline.ingest(...)`，按场景选用即可。

## 相关文档

- [rag.md](rag.md) — 分块器、`RagPipeline`、检索与增强问答全链路。
- [vector-stores.md](vector-stores.md) — 14 种向量库适配（`InMemory` + 13 种外部库）。
- [COOKBOOK.md 场景 18](COOKBOOK.md) — 「文档导入 + 向量入库 + 检索」端到端可运行菜谱。
