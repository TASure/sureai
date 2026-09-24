# 向量库适配（Vector Stores）

sure-ai-rag 通过 `VectorStore` SPI 抽象屏蔽底层向量库差异。业务代码面向接口编程，
可在内存实现与外部向量库之间无缝切换。

## 架构

```
RagPipeline
   └── Retriever（VectorRetriever / HybridRetriever）
            └── VectorStore（接口）
                   ├── InMemoryVectorStore   进程内、余弦相似度、暴力检索（默认，零依赖）
                   ├── MilvusVectorStore     Milvus 2.x REST（v2 vectordb 端点）
                   └── ChromaVectorStore     Chroma REST v1
```

- `VectorStore`：`add / addAll / delete / clear / size / similaritySearch`。
- 运行期仅依赖 sure-core + sure-ai-core；外部向量库实现只用 JDK
  `java.net.http.HttpClient`，**不引入任何 Milvus/Chroma/JDBC 客户端库**。
- 外部向量库需自行部署；客户端只负责按协议序列化请求、解析响应，并把各家的
  “距离”统一映射为“相似度得分”（越大越相似）。

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

- **自行部署**：Milvus / Chroma / pgvector 均需独立部署与运维，客户端只做协议适配。
- **网络超时**：默认 10s，生产环境按 P99 延迟调整；外部库不可用时操作会抛
  `AiException`（包装 IOException / 非 2xx 响应）。
- **批量大小**：REST insert 单次建议控制在数百至数千条，过大请自行分批。
- **维度一致**：写入向量维度必须等于集合创建维度，否则服务端报错。
- **零真实网络测试**：`sure-ai-rag` 的单元测试均用本地 `com.sun.net.httpserver.HttpServer`
  mock 外部 REST，不依赖任何真实服务。
