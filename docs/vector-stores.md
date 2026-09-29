# 向量库适配（Vector Stores）

sure-ai-rag 通过 `VectorStore` SPI 抽象屏蔽底层向量库差异。业务代码面向接口编程，
可在内存实现与外部向量库之间无缝切换。

## 架构

```
RagPipeline
   └── Retriever（VectorRetriever / HybridRetriever / 高级检索策略）
            └── VectorStore（接口）
                   ├── InMemoryVectorStore   进程内、余弦相似度、暴力检索（默认，零依赖）
                   ├── MilvusVectorStore     Milvus 2.x REST（v2 vectordb 端点）
                   ├── ChromaVectorStore     Chroma REST v1
                   ├── QdrantVectorStore     Qdrant REST（/collections/{name}/points）
                   ├── PineconeVectorStore   Pinecone data-plane REST（/vectors/upsert）
                   ├── WeaviateVectorStore   Weaviate REST + GraphQL（/v1/graphql）
                   ├── ElasticsearchVectorStore  Elasticsearch REST（余弦 kNN）
                   ├── OpenSearchVectorStore     OpenSearch REST（cosinesimil kNN）
                   └── RedisVectorStore     Redis Stack / RediSearch（RESP over JDK Socket）
```

- `VectorStore`：`add / addAll / delete / clear / size / similaritySearch`，v1.8.0 起新增
  带 `FilterExpression` 的 default 重载方法（向后兼容，见下文「元数据过滤」）。
- 运行期仅依赖 sure-core + sure-ai-core；外部向量库实现只用 JDK
  `java.net.http.HttpClient`（Redis 用 JDK `java.net.Socket` 走 RESP），
  **不引入任何 Milvus/Chroma/Qdrant/Pinecone 等官方客户端库**。
- 外部向量库需自行部署；客户端只负责按协议序列化请求、解析响应，并把各家的
  “距离”统一映射为“相似度得分”（越大越相似）。

## 向量库总览

| 实现类 | 协议 | 鉴权方式 | 关键端点 | metadata filter | 得分口径 |
| --- | --- | --- | --- | --- | --- |
| `InMemoryVectorStore` | 进程内 | 无 | — | 默认忽略（沿用旧行为） | 余弦相似度 [-1,1] |
| `ChromaVectorStore` | HTTP REST v1 | `X-Chroma-Token`（可选） | `/api/v1/collections/{id}/query` | 默认忽略 | L2: `1/(1+d)`；COSINE: `1-d` |
| `MilvusVectorStore` | HTTP REST v2 | `Bearer <key>`（可选） | `/v2/vectordb/entities/search` | 默认忽略 | COSINE/IP 原样；L2: `1/(1+d)` |
| `QdrantVectorStore` | HTTP REST | `api-key` 头（可选） | `/collections/{name}/points/search` | ✅ Qdrant DSL | COSINE/DOT 原样；EUCLID: `1/(1+d)` |
| `PineconeVectorStore` | HTTP REST | `Api-Key` 头（可选） | `/vectors/upsert`、`/query` | ✅ Pinecone filter | 相似度原样（越大越相似） |
| `WeaviateVectorStore` | HTTP REST + GraphQL | `Bearer <key>`（可选） | POST `/v1/graphql`（Aggregate/Get） | ✅ Weaviate Where | 距离还原为相似度 |
| `ElasticsearchVectorStore` | HTTP REST | `ApiKey <credential>`（可选） | `/{index}/_search`（knn） | ✅ ES Query DSL | cosineSimilarity 原样 |
| `OpenSearchVectorStore` | HTTP REST | `ApiKey <credential>`（可选） | `/{index}/_search`（knn） | ✅ OpenSearch Query DSL | cosinesimil 距离→相似度 |
| `RedisVectorStore` | **RESP over TCP（JDK Socket，非 HTTP）** | `AUTH <password>`（可选） | `FT.CREATE` / `FT.SEARCH ... KNN` | ✅ RediSearch filter 语法 | 余弦距离 `1 - distance` |

> 协议说明：Redis 无通用 HTTP 向量检索接口，向量检索依赖 RediSearch 模块
> （`FT.CREATE`/`FT.SEARCH`）。本实现用 JDK `java.net.Socket` 手写最小 RESP2 客户端
> （`*n\r\n$len\r\narg\r\n` 编解码），向量以小端 float32 blob 传入——这与其余 8 个库的
> HTTP REST 不同，但 Socket 为 JDK 自带，仍满足「零新运行期依赖」红线。

## 距离 → 相似度映射

不同向量库返回的“距离”方向与量纲不一致，适配层统一转换为 `[SimilaritySearchResult].score`：

| 度量类型 | 库侧返回 | 映射规则 | score 区间 |
| --- | --- | --- | --- |
| Milvus COSINE | distance 即余弦相似度 | `score = distance` | [-1, 1] |
| Milvus IP（内积） | distance 即内积 | `score = distance` | 视向量而定 |
| Milvus L2 | 欧氏距离（越小越近） | `score = 1 / (1 + distance)` | (0, 1] |
| Chroma L2（默认） | L2 平方距离 | `score = 1 / (1 + distance)` | (0, 1] |
| Chroma COSINE | 余弦距离 | `score = 1 - distance` | [0, 1] |

## MilvusVectorStore

基于 Milvus 2.x RESTful API（端口默认 `19531`）。

**配置项**

| 项 | 说明 | 默认 |
| --- | --- | --- |
| `baseUrl` | Milvus REST 地址 | `http://localhost:19531` |
| `apiKey` | Milvus 2.3+ API Key，非空时发 `Authorization: Bearer <key>` | 不发送鉴权头 |
| `collectionName` | 集合名（必填） | — |
| `dimension` | 向量维度（必填，>0） | — |
| `metricType` | `COSINE` / `IP` / `L2` | `COSINE` |
| `autoCreateCollection` | 构造时自动建集合（幂等，已存在则忽略） | `true` |
| `timeout` | 请求超时 | 10s |

**端点**：建集合 `POST /v2/vectordb/collections/create`；写入
`POST /v2/vectordb/entities/insert`；检索 `POST /v2/vectordb/entities/search`；
删除 `POST /v2/vectordb/entities/delete`（`filter: "id in [\"...\"]"`）；
清空走 drop 集合后重建。

> 限制：`size()` 在 v2 REST 下无可靠的逐集合计数端点，固定返回 `-1`。

**示例**

```java
import com.sure.ai.rag.store.MilvusVectorStore;

VectorStore store = MilvusVectorStore.builder()
        .baseUrl("http://localhost:19531")
        .apiKey("mk-root-...")
        .collectionName("docs")
        .dimension(1024)
        .metricType(MilvusVectorStore.MetricType.COSINE)
        .build();
```

## ChromaVectorStore

基于 Chroma REST API v1（端口默认 `8000`）。构造时按名 get-or-create 集合并缓存
`collection_id`。

**配置项**

| 项 | 说明 | 默认 |
| --- | --- | --- |
| `baseUrl` | Chroma 地址 | `http://localhost:8000` |
| `token` | 非空时发 `X-Chroma-Token: <token>` | 不发送鉴权头 |
| `collectionName` | 集合名（必填） | — |
| `distanceFunction` | `L2` / `COSINE`（写入集合 metadata `hnsw:space`） | `L2` |
| `autoCreateCollection` | 集合不存在时自动创建 | `true` |

**端点**：取集合 `GET /api/v1/collections/{name}`（404 则
`POST /api/v1/collections`）；写入 `POST .../collections/{id}/add`；查询
`POST .../collections/{id}/query`；删除 `POST .../collections/{id}/delete`。

> 限制：`size()` 固定返回 `-1`；`clear()` 通过 drop 集合重建实现。

**示例**

```java
import com.sure.ai.rag.store.ChromaVectorStore;

VectorStore store = ChromaVectorStore.builder()
        .baseUrl("http://localhost:8000")
        .collectionName("docs")
        .distanceFunction(ChromaVectorStore.DistanceFunction.COSINE)
        .build();
```

## QdrantVectorStore

基于 Qdrant REST API（端口默认 `6333`）。鉴权走 `api-key` 请求头。

```java
import com.sure.ai.rag.store.QdrantVectorStore;

VectorStore store = QdrantVectorStore.builder()
        .baseUrl("http://localhost:6333")
        .apiKey("...")                       // 可选，不发则无鉴权头
        .collectionName("docs")
        .dimension(1024)
        .distance(QdrantVectorStore.Distance.COSINE)  // 默认 COSINE，另支持 DOT / EUCLID
        .autoCreateCollection(true)          // 默认 false
        .build();
```

端点：建集合 `PUT /collections/{name}`；写入 `PUT /collections/{name}/points?wait=true`；
检索 `POST /collections/{name}/points/search`；删除 `POST .../points/delete`；
计数 `POST .../points/count`。`size()` 可用（count 端点）。

## PineconeVectorStore

基于 Pinecone data-plane REST。`baseUrl` 填索引主机名（如
`https://my-index-xxx.svc.pinecone.io`），鉴权走 `Api-Key` 头。Pinecone 为 serverless，
无需指定 dimension / collection。

```java
import com.sure.ai.rag.store.PineconeVectorStore;

VectorStore store = PineconeVectorStore.builder()
        .baseUrl("https://my-index-xxx.svc.pinecone.io")
        .apiKey("pc-...")
        .namespace("docs")                   // 可选，留空用默认 namespace
        .build();
```

端点：写入 `POST /vectors/upsert`；检索 `POST /query`；删除 `POST /vectors/delete`。

## WeaviateVectorStore

基于 Weaviate REST + GraphQL（端口默认 `8080`）。鉴权走 `Authorization: Bearer <apiKey>`。
检索用 GraphQL `Get{ClassName{...}}`，计数用 `Aggregate{...}`。

```java
import com.sure.ai.rag.store.WeaviateVectorStore;

VectorStore store = WeaviateVectorStore.builder()
        .baseUrl("http://localhost:8080")
        .apiKey("...")                       // 可选，WCD 走 Bearer
        .className("Doc")                    // 必需
        .textProperty("text")                // 原始文本属性名，默认 text
        .autoCreateSchema(true)              // 默认 false
        .build();
```

> 限制：Weaviate 无 truncate，`clear()` 通过 drop class 后按需重建实现。

## ElasticsearchVectorStore

基于 Elasticsearch REST（端口默认 `9200`），用 kNN 向量检索。鉴权走
`Authorization: ApiKey <credential>`，无 apiKey 时不发鉴权头（适配本地无安全 ES）。

```java
import com.sure.ai.rag.store.ElasticsearchVectorStore;

VectorStore store = ElasticsearchVectorStore.builder()
        .baseUrl("http://localhost:9200")
        .apiKey("...")                       // 可选
        .indexName("docs")                   // 必需
        .vectorField("vector")               // 默认 vector
        .textField("text")                   // 默认 text
        .dimension(1024)                     // autoCreateIndex=true 时必需
        .autoCreateIndex(true)               // 默认 false
        .build();
```

## OpenSearchVectorStore

基于 OpenSearch REST（端口默认 `9200`），用 `cosinesimil` kNN。配置与 Elasticsearch
基本一致。

```java
import com.sure.ai.rag.store.OpenSearchVectorStore;

VectorStore store = OpenSearchVectorStore.builder()
        .baseUrl("http://localhost:9200")
        .apiKey("...")                       // 可选
        .indexName("docs")
        .dimension(1024)
        .autoCreateIndex(true)
        .build();
```

## RedisVectorStore（RESP over TCP，非 HTTP）

基于 Redis Stack（RediSearch）的向量检索。**与其余 8 个库不同**：Redis 无通用 HTTP 向量
检索接口，本实现用 JDK `java.net.Socket` 手写最小 RESP2 客户端，向量以小端 float32 blob
传入 `FT.SEARCH ...=>[KNN]`。这是协议事实，不是遗漏——Socket 为 JDK 自带，零新依赖。

```java
import com.sure.ai.rag.store.RedisVectorStore;

VectorStore store = RedisVectorStore.builder()
        .host("localhost")                   // 默认 localhost
        .port(6379)                          // 默认 6379
        .password("...")                     // 可选，走 AUTH
        .indexName("docs-idx")               // 必需
        .keyPrefix("doc:")                   // HSET key 前缀，默认 doc:
        .vectorField("vector")               // 默认 vector
        .textField("text")                   // 默认 text
        .dimension(1024)
        .algo(RedisVectorStore.VectorAlgo.FLAT)  // FLAT（小数据精确）/ HNSW（大数据近似）
        .tagFields("tenant", "status")       // 声明为 TAG 字段（等值/IN 过滤）
        .numericFields("score", "year")      // 声明为 NUMERIC 字段（范围过滤）
        .autoCreateIndex(true)               // 默认 false
        .build();
```

命令约定：建索引 `FT.CREATE ... SCHEMA vec VECTOR FLAT 6 TYPE FLOAT32 ...`；
写入 `HSET doc:id vec <blob> text <txt> ...`；检索
`FT.SEARCH idx "(filter)=>[KNN k @vec $blob]" PARAMS 2 blob <blob> SORTBY __vec_score`。
COSINE 下 `__vec_score` 为余弦距离（0 相同、2 相反），适配层还原为 `cosine = 1 - distance`。

## 元数据过滤（Metadata Filter）

v1.8.0 引入可移植的过滤表达式 `FilterExpression`（`com.sure.ai.rag.store.filter`）：
业务代码面向抽象表达式编程，再由各方言翻译器转成库原生过滤结构，切换向量库时过滤逻辑
无需重写。

表达式为密封层级：字段条件 `eq / ne / in / gt / gte / lt / lte`，逻辑组合 `And / Or / Not`。
静态工厂 `eq(..)` 等 + fluent `and(..) / or(..) / not()` 组合：

```java
import com.sure.ai.rag.store.filter.FilterExpression;
import static com.sure.ai.rag.store.filter.FilterExpression.eq;
import static com.sure.ai.rag.store.filter.FilterExpression.in;
import static com.sure.ai.rag.store.filter.FilterExpression.gt;

FilterExpression filter = eq("tenant", "acme")
        .and(in("status", List.of("ok", "pending"))
                .or(gt("score", 0.8)));
```

把过滤表达式传给 `similaritySearch` 的带 filter 重载（v1.8.0 在 `VectorStore` SPI 上新增的
default 方法，既有 InMemory/Chroma/Milvus 实现默认忽略 filter、行为不变）：

```java
// 带 filter（可叠加 minScore）
List<SimilaritySearchResult> hits =
        store.similaritySearch(queryVec, 4, filter);

List<SimilaritySearchResult> hits2 =
        store.similaritySearch(queryVec, 4, 0.5, filter);
```

六种方言翻译器（均实现 `FilterTranslator<T>`，`translate(filter)` 返回库原生结构；
入参 `null` 表示不过滤、返回 `null`）：

| 翻译器 | 目标结构 |
| --- | --- |
| `QdrantFilterTranslator` | Qdrant filter DSL（JsonObject） |
| `PineconeFilterTranslator` | Pinecone filter（JsonObject） |
| `WeaviateFilterTranslator` | Weaviate Where 过滤（JsonObject） |
| `ElasticsearchFilterTranslator` | ES Query DSL（JsonObject） |
| `OpenSearchFilterTranslator` | OpenSearch Query DSL（JsonObject） |
| `RedisFilterTranslator` | RediSearch filter 字符串（构造时传入 `numericFields`） |

```java
QdrantFilterTranslator translator = new QdrantFilterTranslator();
JsonObject nativeFilter = translator.translate(filter);   // 随 REST 请求体一起序列化
```

> 数值字段约定：`Vector.metadata` 为 `Map<String,String>`，跨库数值通常以字符串落库；
> 构造范围条件（`gt/gte/lt/lte`）时传入 `Number`，翻译器负责按方言映射为对应类型。
> Redis 的 TAG/NUMERIC 字段需在建索引时通过 `.tagFields(..) / .numericFields(..)` 声明。

## 与 RagPipeline 集成

```java
RagPipeline pipeline = RagPipeline.builder()
        .vectorStore(MilvusVectorStore.builder()
                .collectionName("docs")
                .dimension(1024)
                .build())
        .embeddingProvider(...)
        .chatClient(...)
        .build();
```

不配置 `vectorStore` 时默认使用 `InMemoryVectorStore`。

## pgvector 适配（参考实现）

sure-ai-rag 运行期**不依赖**任何 JDBC 驱动。如需接入 PostgreSQL +
[pgvector](https://github.com/pgvector/pgvector)，请在你的应用工程里自行引入
`org.postgresql:postgresql` 驱动，并实现 `VectorStore` 接口（以下为可直接复制的参考代码）：

```java
import java.sql.*;
import java.util.*;

import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.VectorStore;

/**
 * pgvector 适配示例：余弦距离使用 <=> 操作符。
 * 需自行在应用工程引入 postgresql JDBC 驱动（rag 模块不依赖）。
 */
public class PgVectorStore implements VectorStore {

	private final DataSource dataSource;
	private final String table;

	public PgVectorStore(DataSource dataSource, String table) {
		this.dataSource = dataSource;
		this.table = table;
	}

	/** 建表：vector(N) 与余弦距离索引。 */
	public void createTable(int dimension) throws SQLException {
		try (Connection c = dataSource.getConnection();
				Statement st = c.createStatement()) {
			st.execute("CREATE EXTENSION IF NOT EXISTS vector");
			st.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
					+ "id text PRIMARY KEY, "
					+ "embedding vector(" + dimension + "), "
					+ "text text, "
					+ "metadata jsonb)");
			st.execute("CREATE INDEX IF NOT EXISTS " + table + "_cos_idx "
					+ "ON " + table + " USING hnsw (embedding vector_cosine_ops)");
		}
	}

	@Override
	public void add(Vector v) {
		addAll(List.of(v));
	}

	@Override
	public void addAll(List<Vector> batch) {
		String sql = "INSERT INTO " + table
				+ "(id, embedding, text, metadata) VALUES (?, ?::vector, ?, ?)"
				+ " ON CONFLICT (id) DO UPDATE SET embedding=EXCLUDED.embedding,"
				+ " text=EXCLUDED.text, metadata=EXCLUDED.metadata";
		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(sql)) {
			for (Vector v : batch) {
				ps.setString(1, v.id());
				ps.setString(2, toPgVectorLiteral(v.embedding()));
				ps.setString(3, v.text());
				ps.setString(4, toJson(v.metadata()));
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			throw new IllegalStateException("pgvector 写入失败", e);
		}
	}

	@Override
	public boolean delete(String id) {
		String sql = "DELETE FROM " + table + " WHERE id = ?";
		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, id);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			throw new IllegalStateException("pgvector 删除失败", e);
		}
	}

	@Override
	public void clear() {
		try (Connection c = dataSource.getConnection();
				Statement st = c.createStatement()) {
			st.execute("TRUNCATE " + table);
		} catch (SQLException e) {
			throw new IllegalStateException("pgvector 清空失败", e);
		}
	}

	@Override
	public int size() {
		try (Connection c = dataSource.getConnection();
				ResultSet rs = c.createStatement()
						.executeQuery("SELECT COUNT(*) FROM " + table)) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			throw new IllegalStateException("pgvector 计数失败", e);
		}
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] q, int topK) {
		return similaritySearch(q, topK, Double.NEGATIVE_INFINITY);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] q, int topK, double minScore) {
		// <=> 为余弦距离：0 相同、2 相反；相似度 = 1 - distance。
		String sql = "SELECT id, embedding::text, text, metadata, "
				+ "1 - (embedding <=> ?::vector) AS score "
				+ "FROM " + table + " ORDER BY embedding <=> ?::vector LIMIT ?";
		List<SimilaritySearchResult> out = new ArrayList<>();
		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, toPgVectorLiteral(q));
			ps.setString(2, toPgVectorLiteral(q));
			ps.setInt(3, topK);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					double score = rs.getDouble("score");
					if (score < minScore) {
						continue;
					}
					out.add(new SimilaritySearchResult(
							rs.getString("id"),
							parseVector(rs.getString("embedding")),
							rs.getString("text"),
							parseMetadata(rs.getString("metadata")),
							score));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("pgvector 检索失败", e);
		}
		return out;
	}

	private static String toPgVectorLiteral(float[] v) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < v.length; i++) {
			if (i > 0) sb.append(',');
			sb.append(v[i]);
		}
		return sb.append(']').toString();
	}

	private static float[] parseVector(String text) {
		String[] parts = text.replace("[", "").replace("]", "").split(",");
		float[] v = new float[parts.length];
		for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i]);
		return v;
	}

	private static String toJson(Map<String, String> m) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> e : m.entrySet()) {
			if (!first) sb.append(',');
			sb.append('"').append(e.getKey()).append("\":\"")
			  .append(e.getValue()).append('"');
			first = false;
		}
		return sb.append('}').toString();
	}

	private static Map<String, String> parseMetadata(String json) {
		// 生产环境建议用项目自带的 com.sure.ai.internal.json.Json 解析，
		// 此处省略以保持示例紧凑。
		return Collections.emptyMap();
	}
}
```

## 注意事项

- **自行部署**：Milvus / Chroma / Qdrant / Pinecone / Weaviate / Elasticsearch / OpenSearch /
  Redis（RediSearch）/ pgvector 均需独立部署与运维，客户端只做协议适配。
- **网络超时**：默认 10s，生产环境按 P99 延迟调整；外部库不可用时操作会抛
  `AiException`（包装 IOException / 非 2xx 响应；Redis 为 Socket 异常）。
- **批量大小**：REST insert 单次建议控制在数百至数千条，过大请自行分批。
- **维度一致**：写入向量维度必须等于集合创建维度，否则服务端报错。
- **零真实网络测试**：`sure-ai-rag` 的单元测试均用本地 `com.sun.net.httpserver.HttpServer`
  mock 外部 REST，不依赖任何真实服务。
