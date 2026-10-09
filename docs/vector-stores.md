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
                   ├── RedisVectorStore     Redis Stack / RediSearch（RESP over JDK Socket）
                   ├── PgVectorStore       PostgreSQL + pgvector（前端/后端协议 over JDK Socket，v2.6.0）
                   ├── TypesenseVectorStore  Typesense REST（X-TYPESENSE-API-KEY，v2.6.0）
                   ├── CassandraVectorStore   Cassandra 5.x SAI（CQL 二进制协议 v4 over Socket，v2.6.0）
                   ├── MongoDbVectorStore    MongoDB 7.0+/Atlas（OP_MSG + 最小 BSON over Socket，v2.6.0）
                   └── Neo4jVectorStore     Neo4j 向量索引（HTTP tx/commit JSON，v2.6.0）
```

- `VectorStore`：`add / addAll / delete / clear / size / similaritySearch`，v1.8.0 起新增
  带 `FilterExpression` 的 default 重载方法（向后兼容，见下文「元数据过滤」）。
- 运行期仅依赖 sure-core + sure-ai-core；外部向量库实现只用 JDK
  `java.net.http.HttpClient`（Redis / PGVector / Cassandra / MongoDB 用 JDK
  `java.net.Socket` 手写各原生协议最小子集），**不引入任何 Milvus/Chroma/Qdrant/
  Pinecone/PostgreSQL/DataStax/MongoDB-driver/Neo4j-driver 等官方客户端库**。
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
| `PgVectorStore`（v2.6.0） | **PG 前端/后端协议 over TCP（JDK Socket）** | `trust` / 明文密码（不支持 md5/SCRAM） | Simple Query `'Q'`（无参数化） | ✅ `PgVectorFilterTranslator`（`metadata->>'k'`） | 余弦距离 `<=>` → `1 - distance` |
| `TypesenseVectorStore`（v2.6.0） | HTTP REST | `X-TYPESENSE-API-KEY` 头 | `/collections/{coll}/documents/search` | ✅ `TypesenseFilterTranslator`（`filter_by`） | `1 - vector_distance` |
| `CassandraVectorStore`（v2.6.0） | **CQL 二进制协议 v4 over TCP（JDK Socket）** | 无认证 / SASL PLAIN（不支持多轮 SCRAM） | `QUERY`（无 PREPARE 绑定变量） | ✅ `CassandraFilterTranslator`（`metadata['k']`，仅 Eq/In/And/Or） | `similarity_cosine` 原样 [-1,1] |
| `MongoDbVectorStore`（v2.6.0） | **OP_MSG + 最小 BSON over TCP（JDK Socket）** | 未实现 SCRAM（需 `--noauth`） | `aggregate`（`$vectorSearch`） | ✅ `MongoDbFilterTranslator`（BSON 谓词，作 `$vectorSearch.filter`） | `vectorSearchScore` |
| `Neo4jVectorStore`（v2.6.0） | HTTP 事务性 API（`tx/commit` JSON） | HTTP Basic `base64(user:password)` | `POST /db/{db}/tx/commit` | ✅ `Neo4jFilterTranslator`（`WHERE n.prop` 后过滤） | 向量索引 cosine 得分 |

> 协议说明：Redis 无通用 HTTP 向量检索接口，向量检索依赖 RediSearch 模块
> （`FT.CREATE`/`FT.SEARCH`）。本实现用 JDK `java.net.Socket` 手写最小 RESP2 客户端
> （`*n\r\n$len\r\narg\r\n` 编解码），向量以小端 float32 blob 传入。v2.6.0 起，
> `PgVectorStore`（PG 前端/后端协议）、`CassandraVectorStore`（CQL v4）、
> `MongoDbVectorStore`（OP_MSG+BSON）同为 Socket 原生协议客户端——这与其余库的
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

基于 Redis Stack（RediSearch）的向量检索。Redis 无通用 HTTP 向量
检索接口，本实现用 JDK `java.net.Socket` 手写最小 RESP2 客户端，向量以小端 float32 blob
传入 `FT.SEARCH ...=>[KNN]`。这是协议事实，不是遗漏——Socket 为 JDK 自带，零新依赖。
v2.6.0 起 PgVector / Cassandra / MongoDB 同为 Socket 原生协议客户端（见后文），Redis 是其中最早的一个。

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

## PgVectorStore（v2.6.0，PG 原生协议 over Socket）

基于 PostgreSQL + [`pgvector`](https://github.com/pgvector/pgvector) 扩展。**零 pgjdbc**：
用 JDK `Socket` 实现 PG 前端/后端协议最小子集（StartupMessage + 认证 + Simple Query）。

**配置项**

| 项 | 说明 | 默认 |
| --- | --- | --- |
| `host` / `port` | PG 地址 | `localhost` / `5432` |
| `database` | 数据库名（必填） | — |
| `user` / `password` | 登录名 / 明文密码 | — |
| `table` | 表名（必填） | — |
| `dimension` | 向量维度（`autoCreate=true` 时必填） | — |
| `autoCreate` | 构造时 best-effort 建扩展与表 | `false` |
| `timeout` | 连接/读取超时 | 10s |

```java
import com.sure.ai.rag.store.pgvector.PgVectorStore;

VectorStore store = PgVectorStore.builder()
        .host("localhost")
        .port(5432)
        .database("vectordb")
        .user("postgres")
        .password("secret")
        .table("docs")
        .dimension(1024)
        .autoCreate(true)            // 构造期 CREATE EXTENSION IF NOT EXISTS vector + CREATE TABLE
        .build();
```

**建表约定**：`(id text PRIMARY KEY, text text, metadata jsonb, embedding vector(d))`。
检索 `ORDER BY embedding <=> '[...]' LIMIT n`（余弦距离 `<=>` ∈ [0,2]，还原 `cosine = 1 - distance`）；
写入 `INSERT ... ON CONFLICT (id) DO UPDATE`；计数 `SELECT COUNT(*)`（`size()` 可用）。

> **能力与限制（如实标注）**：
> - 认证仅支持 `AuthenticationCleartextPassword`（明文）与 `AuthenticationOk`（无密码）。
>   服务端若要求 `md5` 或 `scram-sha-256`（SASL）会抛明确异常——请在 `pg_hba.conf`
>   配置 `trust` 或 `password`（明文）认证。
> - 仅实现 Simple Query 协议（`'Q'`），**不支持参数化查询**（Prepared Statement / Bind 未实现）；
>   SQL 字面量经单引号翻倍转义（`'`→`''`）内联拼接，依赖 `standard_conforming_strings=on`。
> - 每次操作新建**短连接**（即连即认证、即执行、即关闭），无共享连接并发问题。
>
> 协议来源：[Message Formats](https://www.postgresql.org/docs/current/protocol-message-formats.html)、
> [Message Flow](https://www.postgresql.org/docs/current/protocol-flow.html)。

## TypesenseVectorStore（v2.6.0，HTTP REST）

基于 Typesense REST API（端口默认 `8108`）。仅依赖 JDK `HttpClient`，不引入官方客户端。
鉴权走 `X-TYPESENSE-API-KEY` 请求头；向量搜索用 `vector_query=vec:([...],k:n)`（`q=*` 通配），
响应 `hits[].vector_distance` 为余弦距离（0 最相似），还原 `cosine = 1 - vector_distance`。

```java
import com.sure.ai.rag.store.typesense.TypesenseVectorStore;

VectorStore store = TypesenseVectorStore.builder()
        .baseUrl("http://localhost:8108")
        .apiKey("xyz...")                    // 非空时发 X-TYPESENSE-API-KEY
        .collectionName("docs")
        .vectorField("vector")               // 默认 vector
        .textField("text")                   // 默认 text
        .dimension(1024)
        .autoCreateCollection(true)          // 默认 false
        .build();
```

**端点**：建集合 `POST /collections`；写入 `POST /collections/{coll}/documents?action=upsert`；
检索 `GET /collections/{coll}/documents/search?q=*&vector_query=...&filter_by=...`；
删除 `DELETE /collections/{coll}/documents/{id}`；计数 `GET /collections/{coll}`（`num_doc`）。

> 集合默认不自动创建；置 `autoCreateCollection(true)` 时按 `num_dim` 建 `float[]` 向量字段。
> 语法来源：[Vector Search](https://typesense.org/docs/0.24.0/api/vector-search.html)、
> [Collections](https://typesense.org/docs/30.0/api/collections.html)。

## CassandraVectorStore（v2.6.0，CQL 二进制协议 v4 over Socket）

基于 Apache Cassandra 5.x（`vector<float,N>` + SAI）。**零 DataStax 驱动**：用 JDK `Socket`
实现 [CQL Binary Protocol v4](https://cassandra.apache.org/doc/latest/cassandra/_attachments/native_protocol_v4.html)
最小子集（9 字节大端帧 + STARTUP/AUTHENTICATE/QUERY/RESULT），短连接即连即走。

```java
import com.sure.ai.rag.store.cassandra.CassandraVectorStore;

VectorStore store = CassandraVectorStore.builder()
        .host("localhost")
        .port(9042)                          // 默认 9042
        .keyspace("vectords")
        .table("docs")
        .user("cassandra")                  // 无认证模式可不设
        .password("cassandra")
        .dimension(1024)
        .autoCreate(true)                   // 默认 false；true 时 best-effort 建 KEYSPACE/TABLE/SAI
        .build();
```

**建表约定**：`(id text PRIMARY KEY, text text, metadata map<text,text>, embedding vector<float,d>)`，
向量 ANN 索引为 SAI（`StorageAttachedIndex`，cosine）。检索
`WHERE embedding ANN OF [...] ORDER BY similarity_cosine(embedding,[...]) LIMIT n`，由
`similarity_cosine` 直接返回余弦相似度（[-1,1]）。

> **能力与限制（如实标注）**：
> - 认证仅支持无认证（`AllowAllAuthenticator`，回 READY）或 `PasswordAuthenticator` 的
>   SASL PLAIN（初始响应 `\0user\0password`）。若 AUTH_RESPONSE 后再发 AUTH_CHALLENGE
>   （多轮 SCRAM/DSE）会抛明确异常——多轮握手超出最小子集范围。
> - 仅实现无绑定变量的 `QUERY`（consistency ONE），**不支持 PREPARE/EXECUTE**，字面量经转义内联拼接。
> - 过滤仅 `Eq/In/And/Or`，且需 `metadata` 列建有 SAI。
>
> 来源：[Native Protocol v4](https://cassandra.apache.org/doc/latest/cassandra/_attachments/native_protocol_v4.html)、
> [Working with Vector Search](https://cassandra.apache.org/doc/latest/cassandra/vector-search/vector-search-working-with.html)。

## MongoDbVectorStore（v2.6.0，OP_MSG + 最小 BSON over Socket）

基于 MongoDB（自管 7.0+ / Atlas 向量搜索）。**零官方驱动**：用 JDK `Socket` 实现
[OP_MSG（opcode 2011）](https://www.mongodb.com/docs/v8.0/reference/mongodb-wire-protocol/)
与最小 BSON 编解码（`Bson`）。

```java
import com.sure.ai.rag.store.mongodb.MongoDbVectorStore;

VectorStore store = MongoDbVectorStore.builder()
        .host("localhost")
        .port(27017)                         // 默认 27017
        .database("vectordb")
        .collection("docs")
        .vectorIndex("docs-vector-idx")      // 已建好的向量搜索索引名（必填）
        .dimension(1024)
        .autoCreate(true)                   // 默认 false
        .build();
```

**文档约定**：`{_id, text, metadata:{...}, embedding:[double...]}`。向量检索用 `$vectorSearch`
聚合阶段（`queryVector/path/index/limit/numCandidates/filter`）+ `$project` 的
`{$meta:"vectorSearchScore"}`。

> **能力与限制（如实标注）**：
> - **未实现 SCRAM-SHA-1/256**（`saslStart/saslContinue` 挑战-响应握手）。请以无认证模式
>   （`--noauth`，或 localhost 例外）部署；若服务端开启 `--auth`，写/聚合命令返回
>   `unauthorized(code=13)`，本实现据此抛明确异常。
> - 仅实现 hello 握手 + `update(upsert)` / `delete` / `count` / `aggregate` 的 OP_MSG。
> - `$vectorSearch` 要求集合已建有向量搜索索引（自管 7.0+ 或 Atlas）。
>
> 来源：[Wire Protocol](https://www.mongodb.com/docs/v8.0/reference/mongodb-wire-protocol/)、
> [BSON Spec](https://bsonspec.org/spec)、
> [$vectorSearch](https://www.mongodb.com/docs/atlas/atlas-vector-search/vector-search-stage/)。

## Neo4jVectorStore（v2.6.0，HTTP 事务性 API）

基于 Neo4j 向量索引。用官方且长期支持的 **HTTP 事务性端点** `POST /db/{db}/tx/commit`
（JSON over HTTP，JDK `HttpClient`），鉴权为 HTTP Basic `base64(user:password)`。

```java
import com.sure.ai.rag.store.neo4j.Neo4jVectorStore;

VectorStore store = Neo4jVectorStore.builder()
        .baseUrl("http://localhost:7474")   // 默认 7474
        .database("neo4j")                  // 默认 neo4j
        .user("neo4j")
        .password("secret")
        .vectorIndex("docs-vector-index")   // 向量索引名（必填）
        .label("Doc")                       // 节点 label，默认 Doc
        .dimension(1024)
        .autoCreateIndex(true)             // 默认 false
        .build();
```

向量检索：`CALL db.index.vector.queryNodes($index,$k,$embedding) YIELD node, score`，
随后 `RETURN node.id/text/metadata, score`；得分即余弦相似度（由向量索引 cosine 配置决定）。
过滤经 `Neo4jFilterTranslator` 翻译为 `WHERE n.prop ...`（后过滤）。

> **协议路径说明**：Neo4j 原生二进制协议为 Bolt（packstream 编解码 + 版本握手 + 分块帧），
> 手写工作量与出错风险较高，本批改用 HTTP `tx/commit`（与 Typesense 同型，零第三方依赖）；
> Bolt/packstream 作为后续路径评估，不在 v2.6.0 实现。
> 来源：[HTTP API: Query](https://neo4j.com/docs/http-api/current/query/)、
> [Vector indexes](https://neo4j.com/docs/cypher-manual/current/indexes/semantic-indexes/vector-indexes/)。

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

十一种方言翻译器（均实现 `FilterTranslator<T>`，`translate(filter)` 返回库原生结构；
入参 `null` 表示不过滤、返回 `null`）：

| 翻译器 | 目标结构 |
| --- | --- |
| `QdrantFilterTranslator` | Qdrant filter DSL（JsonObject） |
| `PineconeFilterTranslator` | Pinecone filter（JsonObject） |
| `WeaviateFilterTranslator` | Weaviate Where 过滤（JsonObject） |
| `ElasticsearchFilterTranslator` | ES Query DSL（JsonObject） |
| `OpenSearchFilterTranslator` | OpenSearch Query DSL（JsonObject） |
| `RedisFilterTranslator` | RediSearch filter 字符串（构造时传入 `numericFields`） |
| `PgVectorFilterTranslator`（v2.6.0） | `metadata->>'field' ...` SQL WHERE 片段 |
| `TypesenseFilterTranslator`（v2.6.0） | Typesense `filter_by` 表达式 |
| `CassandraFilterTranslator`（v2.6.0） | `metadata['k']=v` CQL WHERE（仅 Eq/In/And/Or） |
| `MongoDbFilterTranslator`（v2.6.0） | BSON 谓词（作 `$vectorSearch.filter`） |
| `Neo4jFilterTranslator`（v2.6.0） | `WHERE n.prop ...` Cypher 后过滤 |

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

> **v2.6.0 起已内置** `com.sure.ai.rag.store.pgvector.PgVectorStore`（零 pgjdbc，见上文
> 「PgVectorStore」小节）。以下 JDBC 参考实现保留给希望自己管理连接池 / 使用完整 PG 驱动的
> 场景——sure-ai-rag 运行期**不依赖**任何 JDBC 驱动；接入内置实现时无需自行引入驱动。

sure-ai-rag 运行期**不依赖**任何 JDBC 驱动。如需用自有 PG 驱动 + 连接池接入 PostgreSQL +
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
  Redis（RediSearch）/ PostgreSQL+pgvector / Typesense / Cassandra / MongoDB / Neo4j
  共 13 种外部库均需独立部署与运维，客户端只做协议适配（另有内置 `InMemoryVectorStore`，
  合计 14 种）。
- **网络超时**：默认 10s，生产环境按 P99 延迟调整；外部库不可用时操作会抛
  `AiException`（包装 IOException / 非 2xx 响应；Redis 为 Socket 异常）。
- **批量大小**：REST insert 单次建议控制在数百至数千条，过大请自行分批。
- **维度一致**：写入向量维度必须等于集合创建维度，否则服务端报错。
- **零真实网络测试**：`sure-ai-rag` 的单元测试均用本地 `com.sun.net.httpserver.HttpServer`
  mock 外部 REST，不依赖任何真实服务。
