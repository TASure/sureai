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

package com.sure.ai.rag.store.cassandra;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
import com.sure.ai.rag.store.filter.CassandraFilterTranslator;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.tool.lang.Assert;

/**
 * 基于 Apache Cassandra 5.x（{@code vector<float,N>} + SAI）的外部向量库实现（JDK 原生 CQL 二进制协议客户端）。
 *
 * <p><b>零第三方依赖</b>：不使用 DataStax 官方驱动，直接用 {@link java.net.Socket} 实现
 * <a href="https://cassandra.apache.org/doc/latest/cassandra/_attachments/native_protocol_v4.html">CQL Binary Protocol v4</a>
 * 的最小子集（9 字节大端帧 + STARTUP/AUTHENTICATE/QUERY/RESULT），与 {@code PgVectorStore} 的
 * PostgreSQL 前端协议、{@code RedisVectorStore} 的 RESP 同属「短连接即连即走」形态。</p>
 *
 * <p><b>认证能力（如实标注）</b>：仅支持无认证（{@code AllowAllAuthenticator}，服务端回 READY）
 * 或 {@code PasswordAuthenticator} 的 SASL PLAIN（初始响应 {@code \0user\0password}，随后收 AUTH_SUCCESS）。
 * 若服务端在 AUTH_RESPONSE 后再发 AUTH_CHALLENGE（多轮挑战-响应，如 SCRAM/DSE），本实现抛出明确异常——
 * 多轮 SASL 握手超出本最小子集范围。请在服务端配置对应认证器。</p>
 *
 * <p><b>查询能力（如实标注）</b>：仅实现无绑定变量的 {@code QUERY}（body = long-string CQL +
 * consistency ONE + flags=0），<b>不支持 PREPARE/EXECUTE 绑定变量</b>，字面量一律经转义内联拼接。
 * 每次操作新建一条短连接（STARTUP→认证→QUERY→读结果→关闭）。</p>
 *
 * <p><b>建表约定</b>：{@code (id text PRIMARY KEY, text text, metadata map<text,text>,
 * embedding vector<float,d>)}；向量 ANN 索引为 SAI（{@code StorageAttachedIndex}，cosine）。
 * {@code autoCreate=false}（默认）只读写既有 schema；置 {@code true} 时 best-effort 执行
 * CREATE KEYSPACE/TABLE/SAI。过滤：{@link FilterExpression} 经 {@link CassandraFilterTranslator}
 * 翻译为 {@code metadata['k']=v} 的 WHERE 片段（仅 Eq/In/And/Or，且需 metadata 列建有 SAI）。</p>
 *
 * <p>检索：{@code WHERE embedding ANN OF [...] AND ... ORDER BY ... LIMIT n}，
 * 由 {@code similarity_cosine(embedding,[...])} 直接返回余弦相似度（[-1,1]）。</p>
 *
 * <p>协议/语法来源：
 * <a href="https://cassandra.apache.org/doc/latest/cassandra/_attachments/native_protocol_v4.html">Native Protocol v4</a>、
 * <a href="https://cassandra.apache.org/doc/latest/cassandra/vector-search/vector-search-working-with.html">Working with Vector Search</a>。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class CassandraVectorStore implements VectorStore {

	/** 默认主机。 */
	public static final String DEFAULT_HOST = "localhost";
	/** 默认 CQL 二进制协议端口。 */
	public static final int DEFAULT_PORT = 9042;

	// ===== CQL 二进制协议 v4 opcode =====
	private static final int OP_ERROR = 0x00;
	private static final int OP_STARTUP = 0x01;
	private static final int OP_READY = 0x02;
	private static final int OP_AUTHENTICATE = 0x03;
	private static final int OP_QUERY = 0x07;
	private static final int OP_RESULT = 0x08;
	private static final int OP_AUTH_CHALLENGE = 0x0E;
	private static final int OP_AUTH_RESPONSE = 0x0F;
	private static final int OP_AUTH_SUCCESS = 0x10;

	private static final int RESULT_VOID = 0x0001;
	private static final int RESULT_ROWS = 0x0002;
	private static final int RESULT_SCHEMA_CHANGE = 0x0005;
	private static final short CONSISTENCY_ONE = 0x0001;

	private final String host;
	private final int port;
	private final String keyspace;
	private final String table;
	private final String user;
	private final String password;
	private final int dimension;
	private final boolean autoCreate;
	private final Duration timeout;
	private final CassandraFilterTranslator filterTranslator = new CassandraFilterTranslator();

	private CassandraVectorStore(Builder b) {
		this.host = Assert.notBlank(b.host, "host 不能为空");
		this.port = b.port;
		this.keyspace = Assert.notBlank(b.keyspace, "keyspace 不能为空");
		this.table = Assert.notBlank(b.table, "table 不能为空");
		this.user = b.user;
		this.password = b.password;
		this.dimension = b.dimension;
		this.autoCreate = b.autoCreate;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		if (this.autoCreate) {
			createSchema();
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
		StringBuilder sb = new StringBuilder("INSERT INTO ").append(qualifiedTable())
				.append(" (id, text, metadata, embedding) VALUES (");
		boolean first = true;
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			if (!first) {
				// Cassandra 单条 INSERT 即 upsert；批量逐条执行以保持简单（短连接每条一语句）。
				query(sb.toString());
				sb = new StringBuilder("INSERT INTO ").append(qualifiedTable())
						.append(" (id, text, metadata, embedding) VALUES (");
			}
			first = false;
			sb.append(cqlString(v.id())).append(',')
					.append(cqlString(v.text() == null ? "" : v.text())).append(',')
					.append(mapLiteral(v.metadata())).append(',')
					.append(vectorLiteral(v.embedding())).append(')');
		}
		if (!first) {
			query(sb.toString());
		}
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		query("DELETE FROM " + qualifiedTable() + " WHERE id = " + cqlString(id));
		return true;
	}

	@Override
	public void clear() {
		query("TRUNCATE " + qualifiedTable());
	}

	@Override
	public int size() {
		CqlResult r = query("SELECT COUNT(*) AS c FROM " + qualifiedTable());
		if (!r.rows.isEmpty()) {
			Object v = r.rows.get(0).get("c");
			if (v instanceof Number n) {
				return n.intValue();
			}
			try {
				return (int) Double.parseDouble(String.valueOf(v));
			} catch (NumberFormatException ignored) {
				return -1;
			}
		}
		return -1;
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
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore, FilterExpression filter) {
		Assert.notNull(queryEmbedding, "queryEmbedding 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		String vec = vectorLiteral(queryEmbedding);
		StringBuilder cql = new StringBuilder("SELECT id, text, metadata,")
				.append(" similarity_cosine(embedding, ").append(vec).append(") AS sim FROM ")
				.append(qualifiedTable())
				.append(" WHERE embedding ANN OF ").append(vec);
		String where = filterTranslator.translate(filter);
		if (where != null && !where.isBlank()) {
			cql.append(" AND ").append(where);
		}
		cql.append(" LIMIT ").append(topK);
		CqlResult r = query(cql.toString());
		List<SimilaritySearchResult> results = new ArrayList<>(r.rows.size());
		for (Map<String, Object> row : r.rows) {
			double score = row.get("sim") instanceof Number n ? n.doubleValue() : 0d;
			if (score < minScore) {
				continue;
			}
			String id = String.valueOf(row.get("id"));
			String text = row.get("text") == null ? "" : String.valueOf(row.get("text"));
			@SuppressWarnings("unchecked")
			Map<String, String> metadata = row.get("metadata") instanceof Map
					? (Map<String, String>) row.get("metadata") : new LinkedHashMap<>();
			results.add(new SimilaritySearchResult(id, null, text, metadata, score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * best-effort 建 schema（KEYSPACE/TABLE/向量 SAI）：已存在时由 {@code IF NOT EXISTS} 忽略。
	 *
	 * @return 是否执行
	 */
	public boolean createSchema() {
		if (dimension <= 0) {
			throw new AiException("Cassandra 自动建表需要 dimension，请通过 Builder.dimension 设置");
		}
		query("CREATE KEYSPACE IF NOT EXISTS " + cqlId(keyspace)
				+ " WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
		query("CREATE TABLE IF NOT EXISTS " + qualifiedTable() + " ("
				+ "id text PRIMARY KEY, "
				+ "text text, "
				+ "metadata map<text,text>, "
				+ "embedding vector<float," + dimension + ">)");
		query("CREATE CUSTOM INDEX IF NOT EXISTS " + cqlId(table + "_vec_idx")
				+ " ON " + qualifiedTable() + "(embedding) USING 'StorageAttachedIndex'"
				+ " WITH OPTIONS = {'similarity_function':'COSINE'}");
		return true;
	}

	// ===== CQL 字面量 =====

	private String qualifiedTable() {
		return cqlId(keyspace) + '.' + cqlId(table);
	}

	private static String cqlId(String name) {
		return '"' + name.replace("\"", "\"\"") + '"';
	}

	private static String cqlString(String s) {
		return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
	}

	private static String mapLiteral(Map<String, String> metadata) {
		StringBuilder sb = new StringBuilder('{');
		boolean first = true;
		for (Map.Entry<String, String> e : metadata.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(cqlString(e.getKey())).append(':').append(cqlString(e.getValue()));
		}
		return sb.append('}').toString();
	}

	private static String vectorLiteral(float[] v) {
		StringBuilder sb = new StringBuilder().append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(v[i]);
		}
		return sb.append(']').toString();
	}

	// ===== CQL 二进制协议 v4（最小子集） =====

	/**
	 * 执行一条 CQL（新建短连接：STARTUP → 认证 → QUERY → 读结果 → 关闭）。
	 *
	 * @param cql CQL 语句
	 * @return 结果集（行以列名映射，sim/metadata 特殊解析）
	 */
	private CqlResult query(String cql) {
		int timeoutMs = (int) timeout.toMillis();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMs);
			socket.setSoTimeout(timeoutMs);
			OutputStream out = socket.getOutputStream();
			DataInputStream in = new DataInputStream(socket.getInputStream());
			startup(out, in);
			sendQuery(out, cql);
			return readResult(in);
		} catch (IOException e) {
			throw new AiException("Cassandra CQL 协议调用失败: " + host + ":" + port, e);
		}
	}

	private void startup(OutputStream out, DataInputStream in) throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeShort(body, 1);
		writeString(body, "CQL_VERSION");
		writeString(body, "3.0.0");
		sendFrame(out, OP_STARTUP, body.toByteArray());
		while (true) {
			Frame frame = readFrame(in);
			switch (frame.opcode) {
				case OP_READY -> {
					return;
				}
				case OP_AUTHENTICATE -> {
					// PasswordAuthenticator：SASL PLAIN 初始响应 \0user\0password
					ByteArrayOutputStream token = new ByteArrayOutputStream();
					token.write(0);
					token.write((user == null ? "" : user).getBytes(StandardCharsets.UTF_8));
					token.write(0);
					token.write((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
					sendFrame(out, OP_AUTH_RESPONSE, token.toByteArray());
				}
				case OP_AUTH_SUCCESS -> {
					return;
				}
				case OP_AUTH_CHALLENGE -> throw new AiException(
						"Cassandra 不支持多轮 SASL 认证（AUTH_CHALLENGE）：本实现仅支持无认证或 PasswordAuthenticator PLAIN");
				case OP_ERROR -> throw new AiException("Cassandra 启动错误: " + parseError(frame.body));
				default -> {
					// 其余启动期响应（SUPPORTED 等）忽略。
				}
			}
		}
	}

	private void sendQuery(OutputStream out, String cql) throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeLongString(body, cql);
		writeShort(body, CONSISTENCY_ONE);
		body.write(0x00); // flags=0：无绑定变量/分页/时间戳
		sendFrame(out, OP_QUERY, body.toByteArray());
	}

	private CqlResult readResult(DataInputStream in) throws IOException {
		Frame frame = readFrame(in);
		if (frame.opcode == OP_ERROR) {
			throw new AiException("Cassandra CQL 错误: " + parseError(frame.body));
		}
		if (frame.opcode != OP_RESULT) {
			throw new AiException("Cassandra 预期 RESULT(op=0x08)，实际 opcode=0x"
					+ Integer.toHexString(frame.opcode));
		}
		ByteBuffer bb = ByteBuffer.wrap(frame.body).order(ByteOrder.BIG_ENDIAN);
		int kind = bb.getInt();
		CqlResult result = new CqlResult();
		if (kind == RESULT_VOID || kind == RESULT_SCHEMA_CHANGE) {
			return result;
		}
		if (kind != RESULT_ROWS) {
			// Set_keyspace(3) 等：无行返回。
			return result;
		}
		int flags = bb.getInt();
		int cols = bb.getInt();
		if ((flags & 0x02) != 0) {
			readBytes(bb); // paging_state
		}
		List<String> names = new ArrayList<>(cols);
		if ((flags & 0x04) == 0) {
			if ((flags & 0x01) != 0) {
				readString(bb);
				readString(bb);
			}
			for (int i = 0; i < cols; i++) {
				names.add(readString(bb));
				skipTypeOption(bb);
			}
		}
		int rowCount = bb.getInt();
		for (int r = 0; r < rowCount; r++) {
			Map<String, Object> row = new LinkedHashMap<>();
			for (int c = 0; c < cols; c++) {
				byte[] raw = readBytes(bb);
				String name = c < names.size() ? names.get(c) : "col" + c;
				row.put(name, decodeCell(name, raw));
			}
			result.rows.add(row);
		}
		return result;
	}

	private static Object decodeCell(String name, byte[] raw) {
		if (raw == null) {
			return null;
		}
		if ("metadata".equals(name)) {
			return parseMap(raw);
		}
		if ("sim".equals(name) || raw.length == 8) {
			try {
				return ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).getDouble();
			} catch (RuntimeException ignored) {
				return new String(raw, StandardCharsets.UTF_8);
			}
		}
		return new String(raw, StandardCharsets.UTF_8);
	}

	/** 解析 CQL map&lt;text,text&gt; 序列化：short count + count*(bytes key + bytes value)。 */
	private static Map<String, String> parseMap(byte[] raw) {
		Map<String, String> map = new LinkedHashMap<>();
		ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		try {
			int count = bb.getShort();
			for (int i = 0; i < count; i++) {
				String key = readCqlBytesString(bb);
				String value = readCqlBytesString(bb);
				map.put(key, value);
			}
		} catch (RuntimeException ignored) {
			// 非 map 序列化时按空元数据返回。
		}
		return map;
	}

	private static String readCqlBytesString(ByteBuffer bb) {
		int len = bb.getInt();
		if (len < 0) {
			return null;
		}
		byte[] buf = new byte[len];
		bb.get(buf);
		return new String(buf, StandardCharsets.UTF_8);
	}

	/** 递归跳过 RESULT 列类型 option（map/list/set/UDT/tuple/custom 有嵌套值）。 */
	private static void skipTypeOption(ByteBuffer bb) {
		int id = bb.getShort();
		switch (id) {
			case 0x0000 -> readString(bb); // custom：类名字符串
			case 0x0020 -> skipTypeOption(bb); // list：元素类型
			case 0x0021 -> { // map：key + value 类型
				skipTypeOption(bb);
				skipTypeOption(bb);
			}
			case 0x0022 -> skipTypeOption(bb); // set：元素类型
			case 0x0030 -> { // UDT：ks, name, n, n*(fieldName+type)
				readString(bb);
				readString(bb);
				int n = bb.getShort();
				for (int i = 0; i < n; i++) {
					readString(bb);
					skipTypeOption(bb);
				}
			}
			case 0x0031 -> { // tuple：n + n*type
				int n = bb.getShort();
				for (int i = 0; i < n; i++) {
					skipTypeOption(bb);
				}
			}
			default -> {
				// 原生标量类型（varchar/double/int...）：无嵌套值。
			}
		}
	}

	private static String parseError(byte[] body) {
		ByteBuffer bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
		int code = bb.getInt();
		return "[" + code + "] " + readString(bb);
	}

	// ===== 帧读写基础 =====

	private static void sendFrame(OutputStream out, int opcode, byte[] body) throws IOException {
		ByteArrayOutputStream frame = new ByteArrayOutputStream();
		frame.write(0x04); // 请求版本 v4
		frame.write(0x00); // flags
		frame.write(0x00); // stream 高字节
		frame.write(0x00); // stream 低字节
		frame.write(opcode);
		frame.write((body.length >>> 24) & 0xFF);
		frame.write((body.length >>> 16) & 0xFF);
		frame.write((body.length >>> 8) & 0xFF);
		frame.write(body.length & 0xFF);
		frame.write(body);
		out.write(frame.toByteArray());
		out.flush();
	}

	private static Frame readFrame(DataInputStream in) throws IOException {
		in.readUnsignedByte(); // version（响应为 0x84）
		in.readUnsignedByte(); // flags
		in.readShort(); // stream
		int opcode = in.readUnsignedByte();
		int length = in.readInt();
		byte[] body = readN(in, length);
		return new Frame(opcode, body);
	}

	private static byte[] readN(DataInputStream in, int n) throws IOException {
		byte[] buf = new byte[n];
		in.readFully(buf);
		return buf;
	}

	private static void writeShort(ByteArrayOutputStream out, int v) {
		out.write((v >> 8) & 0xFF);
		out.write(v & 0xFF);
	}

	private static void writeString(ByteArrayOutputStream out, String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		writeShort(out, b.length);
		out.writeBytes(b);
	}

	private static void writeLongString(ByteArrayOutputStream out, String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.write((b.length >>> 24) & 0xFF);
		out.write((b.length >>> 16) & 0xFF);
		out.write((b.length >>> 8) & 0xFF);
		out.write(b.length & 0xFF);
		out.writeBytes(b);
	}

	private static String readString(ByteBuffer bb) {
		int len = bb.getShort() & 0xFFFF;
		byte[] buf = new byte[len];
		bb.get(buf);
		return new String(buf, StandardCharsets.UTF_8);
	}

	private static byte[] readBytes(ByteBuffer bb) {
		int len = bb.getInt();
		if (len < 0) {
			return null;
		}
		byte[] buf = new byte[len];
		bb.get(buf);
		return buf;
	}

	/** 已解析帧。 */
	private static final class Frame {
		private final int opcode;
		private final byte[] body;

		Frame(int opcode, byte[] body) {
			this.opcode = opcode;
			this.body = body;
		}
	}

	/** CQL 结果集。 */
	private static final class CqlResult {
		private final List<Map<String, Object>> rows = new ArrayList<>();
	}

	/**
	 * {@link CassandraVectorStore} 构建器。
	 */
	public static final class Builder {

		private String host = DEFAULT_HOST;
		private int port = DEFAULT_PORT;
		private String keyspace;
		private String table;
		private String user;
		private String password;
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
		 * 设置 keyspace（必需）。
		 *
		 * @param keyspace keyspace 名
		 * @return this
		 */
		public Builder keyspace(String keyspace) {
			this.keyspace = keyspace;
			return this;
		}

		/**
		 * 设置表名（必需）。
		 *
		 * @param table 表名
		 * @return this
		 */
		public Builder table(String table) {
			this.table = table;
			return this;
		}

		/**
		 * 设置用户名（PasswordAuthenticator PLAIN 时使用）。
		 *
		 * @param user 用户名
		 * @return this
		 */
		public Builder user(String user) {
			this.user = user;
			return this;
		}

		/**
		 * 设置密码（PLAIN 认证时使用）。
		 *
		 * @param password 密码
		 * @return this
		 */
		public Builder password(String password) {
			this.password = password;
			return this;
		}

		/**
		 * 设置向量维度（自动建表时必需，&gt;0）。
		 *
		 * @param dimension 维度
		 * @return this
		 */
		public Builder dimension(int dimension) {
			this.dimension = dimension;
			return this;
		}

		/**
		 * 是否构造时自动建 KEYSPACE/TABLE/SAI，默认 false。
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
		 * @return CassandraVectorStore
		 */
		public CassandraVectorStore build() {
			return new CassandraVectorStore(this);
		}
	}
}
