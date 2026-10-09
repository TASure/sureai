/*
 * Copyright (c) 2026 sureai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sure.ai.rag.store.mongodb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.VectorStore;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.MongoDbFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 MongoDB（自管 7.0+ / Atlas 向量搜索）的外部向量库实现（JDK 原生 wire protocol + BSON 客户端）。
 *
 * <p><b>零第三方依赖</b>：不使用 MongoDB 官方驱动，直接用 {@link java.net.Socket} 实现
 * <a href="https://www.mongodb.com/docs/v8.0/reference/mongodb-wire-protocol/">OP_MSG（opcode 2011）</a>
 * 与最小 BSON 编解码（{@link Bson}），与 {@code PgVectorStore}/{@code CassandraVectorStore} 同属短连接形态。</p>
 *
 * <p><b>认证能力（如实标注）</b>：本实现<b>未实现 SCRAM-SHA-1/256</b>（{@code saslStart}/{@code saslContinue}
 * 挑战-响应握手）。请以无认证模式（{@code --noauth}，或 localhost 例外）部署；若服务端开启 {@code --auth}，
 * 写/聚合命令会返回 {@code unauthorized(code=13)}，本实现据此抛出明确异常。</p>
 *
 * <p><b>命令能力（如实标注）</b>：仅实现 hello 握手 + 以下命令的 OP_MSG：
 * {@code update(upsert)}、{@code delete}、{@code count}、{@code aggregate}。
 * 向量检索用 {@code $vectorSearch} 聚合阶段（{@code queryVector/path/index/limit/numCandidates/filter}）
 * + {@code $project} 的 {@code {$meta:"vectorSearchScore"}}；该阶段要求集合已建有向量搜索索引
 * （自管 7.0+ 或 Atlas）。</p>
 *
 * <p>文档约定：{@code {_id, text, metadata:{...}, embedding:[double...]}}。过滤：
 * {@link FilterExpression} 经 {@link MongoDbFilterTranslator} 翻译为 BSON 谓词，作为
 * {@code $vectorSearch.filter} 传入。</p>
 *
 * <p>协议来源：
 * <a href="https://www.mongodb.com/docs/v8.0/reference/mongodb-wire-protocol/">MongoDB Wire Protocol</a>、
 * <a href="https://bsonspec.org/spec">BSON Spec</a>、
 * <a href="https://www.mongodb.com/docs/atlas/atlas-vector-search/vector-search-stage/">$vectorSearch</a>。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class MongoDbVectorStore implements VectorStore {

	/** 默认主机。 */
	public static final String DEFAULT_HOST = "localhost";
	/** 默认端口。 */
	public static final int DEFAULT_PORT = 27017;

	private static final int OP_CODE_OP_MSG = 2011;

	private final String host;
	private final int port;
	private final String database;
	private final String collection;
	private final String vectorIndex;
	private final int dimension;
	private final boolean autoCreate;
	private final Duration timeout;
	private final MongoDbFilterTranslator filterTranslator = new MongoDbFilterTranslator();

	private MongoDbVectorStore(Builder b) {
		this.host = Assert.notBlank(b.host, "host 不能为空");
		this.port = b.port;
		this.database = Assert.notBlank(b.database, "database 不能为空");
		this.collection = Assert.notBlank(b.collection, "collection 不能为空");
		this.vectorIndex = Assert.notBlank(b.vectorIndex, "vectorIndex 不能为空");
		this.dimension = b.dimension;
		this.autoCreate = b.autoCreate;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		if (this.autoCreate) {
			createVectorIndex();
		}
	}

	/**
	 * 创建构建器。
	 *
	 * @return 构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public void add(Vector vector) {
		Assert.notNull(vector, "vector 不能为 null");
		addAll(List.of(vector));
	}

	@Override
	public void addAll(List<Vector> batch) {
		Assert.notNull(batch, "batch 不能为 null");
		if (batch.isEmpty()) {
			return;
		}
		Bson.Doc updatesArr = new Bson.Doc();
		int i = 0;
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			Bson.Doc q = new Bson.Doc().addString("_id", v.id());
			Bson.Doc set = new Bson.Doc()
					.addString("text", v.text() == null ? "" : v.text());
			set.addDoc("metadata", metadataDoc(v.metadata()));
			set.addArray("embedding", doubleArray(v.embedding()));
			Bson.Doc upd = new Bson.Doc()
					.addDoc("q", q)
					.addDoc("u", new Bson.Doc().addDoc("$set", set))
					.addBoolean("upsert", true);
			updatesArr.addDoc(String.valueOf(i++), upd);
		}
		if (i == 0) {
			return;
		}
		Bson.Doc cmd = new Bson.Doc()
				.addString("update", collection)
				.addArray("updates", updatesArr);
		command(cmd);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		Bson.Doc q = new Bson.Doc().addString("_id", id);
		Bson.Doc del = new Bson.Doc().addDoc("q", q).addInt32("limit", 1);
		Bson.Doc cmd = new Bson.Doc()
				.addString("delete", collection)
				.addArray("deletes", new Bson.Doc().addDoc("0", del));
		Map<String, Object> resp = command(cmd);
		Object n = resp.get("n");
		return n instanceof Number num && num.intValue() > 0;
	}

	@Override
	public void clear() {
		Bson.Doc q = new Bson.Doc(); // 空 query = 全部
		Bson.Doc del = new Bson.Doc().addDoc("q", q).addInt32("limit", 0); // limit 0 = 不限
		Bson.Doc cmd = new Bson.Doc()
				.addString("delete", collection)
				.addArray("deletes", new Bson.Doc().addDoc("0", del));
		command(cmd);
	}

	@Override
	public int size() {
		Bson.Doc cmd = new Bson.Doc().addString("count", collection);
		Map<String, Object> resp = command(cmd);
		Object n = resp.get("n");
		return n instanceof Number num ? num.intValue() : -1;
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore) {
		return similaritySearch(queryEmbedding, topK, minScore, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			FilterExpression filter) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, filter);
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore, FilterExpression filter) {
		Assert.notNull(queryEmbedding, "queryEmbedding 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		Bson.Doc vs = new Bson.Doc()
				.addString("index", vectorIndex)
				.addString("path", "embedding")
				.addArray("queryVector", doubleArray(queryEmbedding))
				.addInt32("limit", topK)
				.addInt32("numCandidates", topK * 20);
		Bson.Doc pred = filterTranslator.translate(filter);
		if (pred != null) {
			vs.addDoc("filter", pred);
		}
		Bson.Doc project = new Bson.Doc()
				.addInt32("_id", 1)
				.addInt32("text", 1)
				.addInt32("metadata", 1)
				.addDoc("score", new Bson.Doc().addString("$meta", "vectorSearchScore"));
		Bson.Doc stages = new Bson.Doc()
				.addDoc("0", new Bson.Doc().addDoc("$vectorSearch", vs))
				.addDoc("1", new Bson.Doc().addDoc("$project", project));
		Bson.Doc cmd = new Bson.Doc()
				.addString("aggregate", collection)
				.addDoc("cursor", new Bson.Doc())
				.addArray("pipeline", stages);
		Map<String, Object> resp = command(cmd);
		List<SimilaritySearchResult> results = new ArrayList<>();
		Object cursorObj = resp.get("cursor");
		if (cursorObj instanceof Map<?, ?> cursor) {
			Object batchObj = cursor.get("firstBatch");
			if (batchObj instanceof List<?> batch) {
				for (Object docObj : batch) {
					if (!(docObj instanceof Map<?, ?> doc)) {
						continue;
					}
					Object scoreObj = doc.get("score");
					double score = scoreObj instanceof Number num ? num.doubleValue() : 0d;
					if (score < minScore) {
						continue;
					}
					String id = String.valueOf(doc.get("_id"));
					String text = doc.get("text") == null ? "" : String.valueOf(doc.get("text"));
					Map<String, String> metadata = new LinkedHashMap<>();
					if (doc.get("metadata") instanceof Map<?, ?> meta) {
						for (Map.Entry<?, ?> e : meta.entrySet()) {
							metadata.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
						}
					}
					results.add(new SimilaritySearchResult(id, null, text, metadata, score));
				}
			}
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * best-effort 创建向量搜索索引：服务端不支持 createSearchIndex 时忽略错误。
	 *
	 * @return 是否发送
	 */
	public boolean createVectorIndex() {
		if (dimension <= 0) {
			throw new AiException("MongoDB 自动建索引需要 dimension，请通过 Builder.dimension 设置");
		}
		Bson.Doc field = new Bson.Doc()
				.addString("type", "vector")
				.addInt32("dimensions", dimension)
				.addString("similarity", "cosine");
		Bson.Doc fields = new Bson.Doc().addDoc("embedding", field);
		Bson.Doc mappings = new Bson.Doc().addBoolean("dynamic", false).addDoc("fields", fields);
		Bson.Doc definition = new Bson.Doc().addDoc("mappings", mappings);
		Bson.Doc cmd = new Bson.Doc()
				.addString("createSearchIndex", collection)
				.addString("name", vectorIndex)
				.addDoc("definition", definition);
		try {
			command(cmd);
		} catch (AiException ignored) {
			// 旧版本服务端不支持 createSearchIndex：忽略，索引需离线创建。
		}
		return true;
	}

	// ===== 命令执行（短连接：hello -> command -> 关闭） =====

	private Map<String, Object> command(Bson.Doc command) {
		int timeoutMs = (int) timeout.toMillis();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMs);
			socket.setSoTimeout(timeoutMs);
			OutputStream out = socket.getOutputStream();
			DataInputStream in = new DataInputStream(socket.getInputStream());
			hello(out, in);
			sendOpMsg(out, command.encode());
			Map<String, Object> resp = readOpMsg(in);
			checkOk(resp);
			return resp;
		} catch (IOException e) {
			throw new AiException("MongoDB wire 协议调用失败: " + host + ":" + port, e);
		}
	}

	private void hello(OutputStream out, DataInputStream in) throws IOException {
		Bson.Doc hello = new Bson.Doc().addInt32("hello", 1).addBoolean("helloOk", true);
		sendOpMsg(out, hello.encode());
		readOpMsg(in); // 忽略握手响应（含 maxWireVersion 等）
	}

	private void sendOpMsg(OutputStream out, byte[] body) throws IOException {
		int totalLen = 16 + 4 + 1 + body.length;
		ByteArrayOutputStream frame = new ByteArrayOutputStream();
		writeInt32LE(frame, totalLen);
		writeInt32LE(frame, 1); // requestId
		writeInt32LE(frame, 0); // responseTo
		writeInt32LE(frame, OP_CODE_OP_MSG);
		writeInt32LE(frame, 0); // flagBits
		frame.write(0x00); // section kind 0: body
		frame.write(body);
		out.write(frame.toByteArray());
		out.flush();
	}

	private Map<String, Object> readOpMsg(DataInputStream in) throws IOException {
		byte[] header = readN(in, 16);
		ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		int totalLen = hb.getInt();
		hb.getInt(); // requestId
		hb.getInt(); // responseTo
		int opCode = hb.getInt();
		if (opCode != OP_CODE_OP_MSG) {
			throw new AiException("MongoDB 预期 OP_MSG(2011)，实际 opcode=" + opCode);
		}
		readN(in, 4); // flagBits
		in.read(); // section kind
		int bodyLen = totalLen - 16 - 4 - 1;
		return Bson.parse(readN(in, bodyLen));
	}

	private void checkOk(Map<String, Object> resp) {
		Object ok = resp.get("ok");
		double okv = ok instanceof Number n ? n.doubleValue() : 0d;
		if (okv < 0.999) {
			Object code = resp.get("code");
			Object errmsg = resp.get("errmsg");
			if (code instanceof Number num && num.intValue() == 13) {
				throw new AiException("MongoDB 返回 unauthorized(code=13)：本实现未实现 SCRAM 认证，"
						+ "请以无认证模式部署。errmsg=" + errmsg);
			}
			throw new AiException("MongoDB 命令失败(code=" + code + "): " + errmsg);
		}
	}

	// ===== BSON 构造辅助 =====

	private static Bson.Doc metadataDoc(Map<String, String> metadata) {
		Bson.Doc doc = new Bson.Doc();
		for (Map.Entry<String, String> e : metadata.entrySet()) {
			doc.addString(e.getKey(), e.getValue());
		}
		return doc;
	}

	private static Bson.Doc doubleArray(float[] v) {
		Bson.Doc arr = new Bson.Doc();
		for (int i = 0; i < v.length; i++) {
			arr.addDouble(String.valueOf(i), v[i]);
		}
		return arr;
	}

	private static byte[] readN(DataInputStream in, int n) throws IOException {
		byte[] buf = new byte[n];
		in.readFully(buf);
		return buf;
	}

	private static void writeInt32LE(ByteArrayOutputStream out, int v) {
		out.write(v & 0xFF);
		out.write((v >> 8) & 0xFF);
		out.write((v >> 16) & 0xFF);
		out.write((v >> 24) & 0xFF);
	}

	/**
	 * {@link MongoDbVectorStore} 构建器。
	 */
	public static final class Builder {

		private String host = DEFAULT_HOST;
		private int port = DEFAULT_PORT;
		private String database;
		private String collection;
		private String vectorIndex;
		private int dimension;
		private boolean autoCreate;
		private Duration timeout;

		private Builder() {
		}

		/**
		 * 设置主机。
		 *
		 * @param host 主机
		 * @return this
		 */
		public Builder host(String host) {
			this.host = host;
			return this;
		}

		/**
		 * 设置端口。
		 *
		 * @param port 端口
		 * @return this
		 */
		public Builder port(int port) {
			this.port = port;
			return this;
		}

		/**
		 * 设置数据库名（必需）。
		 *
		 * @param database 数据库名
		 * @return this
		 */
		public Builder database(String database) {
			this.database = database;
			return this;
		}

		/**
		 * 设置集合名（必需）。
		 *
		 * @param collection 集合名
		 * @return this
		 */
		public Builder collection(String collection) {
			this.collection = collection;
			return this;
		}

		/**
		 * 设置向量搜索索引名（必需）。
		 *
		 * @param vectorIndex 向量索引名
		 * @return this
		 */
		public Builder vectorIndex(String vectorIndex) {
			this.vectorIndex = vectorIndex;
			return this;
		}

		/**
		 * 设置向量维度（自动建索引时必需，&gt;0）。
		 *
		 * @param dimension 维度
		 * @return this
		 */
		public Builder dimension(int dimension) {
			this.dimension = dimension;
			return this;
		}

		/**
		 * 是否 best-effort 自动创建向量搜索索引，默认 false。
		 *
		 * @param autoCreate 是否自动创建
		 * @return this
		 */
		public Builder autoCreate(boolean autoCreate) {
			this.autoCreate = autoCreate;
			return this;
		}

		/**
		 * 设置连接/读取超时，默认 10 秒。
		 *
		 * @param timeout 超时
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 构建实例。
		 *
		 * @return MongoDbVectorStore
		 */
		public MongoDbVectorStore build() {
			return new MongoDbVectorStore(this);
		}
	}
}
