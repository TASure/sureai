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

package com.sure.ai.rag.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.RedisFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 Redis Stack（RediSearch）的外部向量库实现。
 *
 * <p><b>协议偏差说明</b>：Redis 无通用 HTTP 接口，向量检索依赖 RediSearch 模块（FT.CREATE/FT.SEARCH）。
 * 本实现用 JDK {@link java.net.Socket} 实现最小 RESP2 客户端（{@code *n\r\n$len\r\narg\r\n} 编解码），
 * 向量以小端 float32 blob 传入。这是与任务书「REST」措辞的合理偏差——Redis 协议事实如此，
 * 仍满足「零新运行期依赖」红线（Socket 为 JDK 自带）。</p>
 *
 * <p>命令约定：</p>
 * <ul>
 *   <li>建索引：{@code FT.CREATE idx ON HASH PREFIX 1 doc: SCHEMA vec VECTOR FLAT 6
 *       TYPE FLOAT32 DIM d DISTANCE_METRIC COSINE text TEXT ...}（元数据字段声明 TAG/NUMERIC）；</li>
 *   <li>写入：{@code HSET doc:id vec <blob> text <txt> ...}；</li>
 *   <li>检索：{@code FT.SEARCH idx "(filter)=>[KNN k @vec $blob]" PARAMS 2 blob <blob>
 *       SORTBY __vec_score ASC LIMIT 0 k DIALECT 2}；</li>
 *   <li>删除：{@code DEL doc:id}；清空：{@code FT.DROPINDEX idx}；计数：{@code FT.INFO} 解析 num_docs。</li>
 * </ul>
 *
 * <p>得分：COSINE 距离度量下 {@code __vec_score} 为余弦距离（0 相同、2 相反），
 * 还原为 {@code cosine = 1 - distance}。索引默认不自动创建。</p>
 *
 * <p>语法来源：<a href="https://redis.io/docs/latest/develop/interact/search-and-query/query/vector-search/">Vector search</a>、
 * <a href="https://redis.io/docs/latest/develop/ai/search-and-query/indexing/field-and-type-options/">Field and type options</a>。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class RedisVectorStore implements VectorStore {

	/** 默认主机。 */
	public static final String DEFAULT_HOST = "localhost";
	/** 默认端口。 */
	public static final int DEFAULT_PORT = 6379;

	private final String host;
	private final int port;
	private final String password;
	private final String indexName;
	private final String keyPrefix;
	private final String vectorField;
	private final String textField;
	private final int dimension;
	private final VectorAlgo algo;
	private final Set<String> tagFields;
	private final Set<String> numericFields;
	private final boolean autoCreateIndex;
	private final Duration timeout;
	private final RedisFilterTranslator filterTranslator;

	private RedisVectorStore(Builder b) {
		this.host = Assert.notBlank(b.host, "host 不能为空");
		this.port = b.port;
		this.password = b.password;
		this.indexName = Assert.notBlank(b.indexName, "indexName 不能为空");
		this.keyPrefix = Assert.notBlank(b.keyPrefix, "keyPrefix 不能为空");
		this.vectorField = Assert.notBlank(b.vectorField, "vectorField 不能为空");
		this.textField = Assert.notBlank(b.textField, "textField 不能为空");
		this.dimension = b.dimension;
		this.algo = b.algo == null ? VectorAlgo.FLAT : b.algo;
		this.tagFields = Set.copyOf(b.tagFields);
		this.numericFields = Set.copyOf(b.numericFields);
		this.autoCreateIndex = b.autoCreateIndex;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		this.filterTranslator = new RedisFilterTranslator(this.numericFields);
		if (this.autoCreateIndex) {
			createIndex();
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
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			List<Object> cmd = new ArrayList<>();
			cmd.add("HSET");
			cmd.add(keyPrefix + v.id());
			cmd.add(vectorField);
			cmd.add(toBlob(v.embedding()));
			cmd.add(textField);
			cmd.add(v.text() == null ? "" : v.text());
			for (Map.Entry<String, String> e : v.metadata().entrySet()) {
				cmd.add(e.getKey());
				cmd.add(e.getValue());
			}
			send(cmd);
		}
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		Object resp = send(List.of("DEL", keyPrefix + id));
		return resp instanceof Long l && l > 0;
	}

	@Override
	public void clear() {
		send(List.of("FT.DROPINDEX", indexName));
		if (autoCreateIndex) {
			createIndex();
		}
	}

	@Override
	public int size() {
		Object resp = send(List.of("FT.INFO", indexName));
		if (resp instanceof List<?> list) {
			for (int i = 0; i + 1 < list.size(); i++) {
				if ("num_docs".equals(String.valueOf(list.get(i)))) {
					try {
						return (int) Double.parseDouble(String.valueOf(list.get(i + 1)));
					} catch (NumberFormatException ignored) {
						return -1;
					}
				}
			}
		}
		return -1;
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK, double minScore) {
		return similaritySearch(queryEmbedding, topK, minScore, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK, FilterExpression filter) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, filter);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore, FilterExpression filter) {
		Assert.notNull(queryEmbedding, "queryEmbedding 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		String filterPart = filterTranslator.translate(filter);
		String preFilter = filterPart == null ? "*" : "(" + filterPart + ")";
		String query = preFilter + "=>[KNN " + topK + " @" + vectorField + " $blob]";
		String scoreField = "__" + vectorField + "_score";
		List<Object> cmd = new ArrayList<>();
		cmd.add("FT.SEARCH");
		cmd.add(indexName);
		cmd.add(query);
		cmd.add("PARAMS");
		cmd.add("2");
		cmd.add("blob");
		cmd.add(toBlob(queryEmbedding));
		cmd.add("SORTBY");
		cmd.add(scoreField);
		cmd.add("ASC");
		cmd.add("LIMIT");
		cmd.add("0");
		cmd.add(String.valueOf(topK));
		cmd.add("DIALECT");
		cmd.add("2");
		Object resp = send(cmd);
		List<SimilaritySearchResult> results = new ArrayList<>();
		if (resp instanceof List<?> list) {
			// list[0] = total；随后交替 key(String) 与 fields(数组)。
			for (int i = 1; i + 1 < list.size(); i += 2) {
				String key = String.valueOf(list.get(i));
				Object fieldsObj = list.get(i + 1);
				if (!(fieldsObj instanceof List<?> fields)) {
					continue;
				}
				Map<String, String> metadata = new LinkedHashMap<>();
				String text = "";
				String distance = null;
				for (int j = 0; j + 1 < fields.size(); j += 2) {
					String name = String.valueOf(fields.get(j));
					String value = String.valueOf(fields.get(j + 1));
					if (scoreField.equals(name)) {
						distance = value;
					} else if (textField.equals(name)) {
						text = value;
					} else if (vectorField.equals(name)) {
						// 跳过向量 blob（二进制，无需回填）。
					} else {
						metadata.put(name, value);
					}
				}
				double score = distance == null ? 0d : 1d - Double.parseDouble(distance);
				if (score < minScore) {
					continue;
				}
				String id = key.startsWith(keyPrefix) ? key.substring(keyPrefix.length()) : key;
				results.add(new SimilaritySearchResult(id, null, text, metadata, score));
			}
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * 创建 RediSearch 索引（best-effort）。
	 *
	 * @return 是否成功
	 */
	public boolean createIndex() {
		if (dimension <= 0) {
			throw new AiException("Redis 创建索引需要 dimension，请通过 Builder.dimension 设置");
		}
		List<Object> cmd = new ArrayList<>();
		cmd.add("FT.CREATE");
		cmd.add(indexName);
		cmd.add("ON");
		cmd.add("HASH");
		cmd.add("PREFIX");
		cmd.add("1");
		cmd.add(keyPrefix);
		cmd.add("SCHEMA");
		cmd.add(vectorField);
		cmd.add("VECTOR");
		cmd.add(algo.name());
		cmd.add("6");
		cmd.add("TYPE");
		cmd.add("FLOAT32");
		cmd.add("DIM");
		cmd.add(String.valueOf(dimension));
		cmd.add("DISTANCE_METRIC");
		cmd.add("COSINE");
		cmd.add(textField);
		cmd.add("TEXT");
		for (String f : tagFields) {
			cmd.add(f);
			cmd.add("TAG");
		}
		for (String f : numericFields) {
			cmd.add(f);
			cmd.add("NUMERIC");
		}
		send(cmd);
		return true;
	}

	private Object send(List<Object> command) {
		int timeoutMs = (int) timeout.toMillis();
		try (Socket socket = new Socket()) {
			socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
			socket.setSoTimeout(timeoutMs);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			if (password != null && !password.isBlank()) {
				out.write(encode(List.of("AUTH", password)));
				out.flush();
				readReply(in);
			}
			out.write(encode(command));
			out.flush();
			return readReply(in);
		} catch (IOException e) {
			throw new AiException("Redis RESP 调用失败: " + host + ":" + port, e);
		}
	}

	private static byte[] toBlob(float[] vector) {
		ByteBuffer bb = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : vector) {
			bb.putFloat(f);
		}
		return bb.array();
	}

	private static void writeRaw(ByteArrayOutputStream out, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.write(bytes, 0, bytes.length);
	}

	private static byte[] encode(List<Object> args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeRaw(out, "*" + args.size() + "\r\n");
		for (Object arg : args) {
			if (arg instanceof byte[] bytes) {
				writeRaw(out, "$" + bytes.length + "\r\n");
				out.write(bytes, 0, bytes.length);
				writeRaw(out, "\r\n");
			} else {
				byte[] bytes = String.valueOf(arg).getBytes(StandardCharsets.UTF_8);
				writeRaw(out, "$" + bytes.length + "\r\n");
				out.write(bytes, 0, bytes.length);
				writeRaw(out, "\r\n");
			}
		}
		return out.toByteArray();
	}

	private static Object readReply(InputStream in) throws IOException {
		int b = in.read();
		if (b == -1) {
			throw new AiException("Redis 连接已关闭");
		}
		return switch (b) {
			case '*' -> readArray(in);
			case '$' -> readBulk(in);
			case ':' -> Long.parseLong(readLine(in));
			case '+' -> readLine(in);
			case '-' -> throw new AiException("Redis 错误: " + readLine(in));
			case '_' -> {
				readLine(in);
				yield null;
			}
			default -> throw new AiException("未知 RESP 类型: " + (char) b);
		};
	}

	private static Object readArray(InputStream in) throws IOException {
		long count = Long.parseLong(readLine(in));
		if (count < 0) {
			return null;
		}
		List<Object> list = new ArrayList<>((int) count);
		for (long i = 0; i < count; i++) {
			list.add(readReply(in));
		}
		return list;
	}

	private static Object readBulk(InputStream in) throws IOException {
		long len = Long.parseLong(readLine(in));
		if (len < 0) {
			return null;
		}
		byte[] buf = new byte[(int) len];
		int read = 0;
		while (read < len) {
			int r = in.read(buf, read, (int) len - read);
			if (r == -1) {
				throw new AiException("Redis bulk 读取不完整");
			}
			read += r;
		}
		int cr = in.read();
		int lf = in.read();
		if (cr != '\r' || lf != '\n') {
			throw new AiException("Redis bulk 结尾 CRLF 缺失");
		}
		return new String(buf, StandardCharsets.UTF_8);
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int prev = -1;
		int c;
		while ((c = in.read()) != -1) {
			if (prev == '\r' && c == '\n') {
				byte[] bytes = out.toByteArray();
				return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
			}
			out.write(c);
			prev = c;
		}
		throw new AiException("Redis 行读取不完整");
	}

	/**
	 * 向量索引算法。
	 */
	public enum VectorAlgo {
		/** FLAT（暴力，小数据集精确）。 */
		FLAT,
		/** HNSW（图索引，大数据集近似）。 */
		HNSW
	}

	/**
	 * {@link RedisVectorStore} 构建器。
	 */
	public static final class Builder {

		private String host = DEFAULT_HOST;
		private int port = DEFAULT_PORT;
		private String password;
		private String indexName;
		private String keyPrefix = "doc:";
		private String vectorField = "vector";
		private String textField = "text";
		private int dimension;
		private VectorAlgo algo = VectorAlgo.FLAT;
		private Set<String> tagFields = new LinkedHashSet<>();
		private Set<String> numericFields = new LinkedHashSet<>();
		private boolean autoCreateIndex;
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
		 * 设置密码（AUTH），为空则不鉴权。
		 *
		 * @param password 密码
		 * @return this
		 */
		public Builder password(String password) {
			this.password = password;
			return this;
		}

		/**
		 * 设置索引名（必需）。
		 *
		 * @param indexName 索引名
		 * @return this
		 */
		public Builder indexName(String indexName) {
			this.indexName = indexName;
			return this;
		}

		/**
		 * 设置 HASH key 前缀，默认 doc:。
		 *
		 * @param keyPrefix key 前缀
		 * @return this
		 */
		public Builder keyPrefix(String keyPrefix) {
			this.keyPrefix = keyPrefix;
			return this;
		}

		/**
		 * 设置向量字段名，默认 vector。
		 *
		 * @param vectorField 向量字段名
		 * @return this
		 */
		public Builder vectorField(String vectorField) {
			this.vectorField = vectorField;
			return this;
		}

		/**
		 * 设置文本字段名，默认 text。
		 *
		 * @param textField 文本字段名
		 * @return this
		 */
		public Builder textField(String textField) {
			this.textField = textField;
			return this;
		}

		/**
		 * 设置向量维度（创建索引时必需，&gt;0）。
		 *
		 * @param dimension 维度
		 * @return this
		 */
		public Builder dimension(int dimension) {
			this.dimension = dimension;
			return this;
		}

		/**
		 * 设置向量索引算法，默认 FLAT。
		 *
		 * @param algo 算法
		 * @return this
		 */
		public Builder algo(VectorAlgo algo) {
			this.algo = algo;
			return this;
		}

		/**
		 * 追加 TAG 类型元数据字段（用于精确匹配过滤）。
		 *
		 * @param fields 字段名
		 * @return this
		 */
		public Builder tagFields(String... fields) {
			for (String f : fields) {
				this.tagFields.add(f);
			}
			return this;
		}

		/**
		 * 追加 NUMERIC 类型元数据字段（用于范围过滤）。
		 *
		 * @param fields 字段名
		 * @return this
		 */
		public Builder numericFields(String... fields) {
			for (String f : fields) {
				this.numericFields.add(f);
			}
			return this;
		}

		/**
		 * 是否构造时自动创建索引，默认 false。
		 *
		 * @param autoCreateIndex 是否自动创建
		 * @return this
		 */
		public Builder autoCreateIndex(boolean autoCreateIndex) {
			this.autoCreateIndex = autoCreateIndex;
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
		 * @return RedisVectorStore
		 */
		public RedisVectorStore build() {
			return new RedisVectorStore(this);
		}
	}
}
