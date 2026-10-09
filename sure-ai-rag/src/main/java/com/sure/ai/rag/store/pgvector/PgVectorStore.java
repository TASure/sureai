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

package com.sure.ai.rag.store.pgvector;

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
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.VectorStore;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.PgVectorFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 PostgreSQL {@code pgvector} 扩展的外部向量库实现（JDK 原生协议客户端）。
 *
 * <p><b>零第三方依赖</b>：不使用 pgjdbc，直接用 {@link java.net.Socket} 实现
 * PostgreSQL 前端/后端协议的最小子集（StartupMessage + 认证 + Simple Query），
 * 工具形态复用 v1.8.0 {@code RedisVectorStore} 的 RESP-over-Socket 经验。</p>
 *
 * <p><b>认证能力（如实标注）</b>：仅支持
 * {@code AuthenticationCleartextPassword}（明文密码）与 {@code AuthenticationOk}（无密码）。
 * 服务端若要求 {@code md5} 或 {@code scram-sha-256}（SASL）认证，本实现会抛出明确异常——
 * SCRAM 为挑战-响应多轮握手，超出本最小子集范围。请在服务端配置
 * {@code pg_hba.conf} 使用 {@code trust} 或 {@code password}（明文）认证。</p>
 *
 * <p><b>查询能力（如实标注）</b>：仅实现 Simple Query 协议（{@code 'Q'} 消息），
 * <b>不支持参数化查询</b>（Prepared Statement / Bind 未实现）。SQL 字面量一律通过
 * 单引号翻倍转义（{@code '} -> {@code ''}）内联拼接，依赖服务端
 * {@code standard_conforming_strings=on}（PG 9.1+ 默认）。每次操作新建一条短连接
 * （连接即认证、即执行、即关闭），与 {@code RedisVectorStore} 一致，天然无共享连接的并发问题。</p>
 *
 * <p><b>建表约定</b>：表结构为
 * {@code (id text PRIMARY KEY, text text, metadata jsonb, embedding vector(d))}。
 * 服务端须已安装 pgvector 扩展（{@code CREATE EXTENSION vector}）。
 * {@code autoCreate=false}（默认）时只读写既有表，不做任何 DDL；
 * 置 {@code true} 时构造期 best-effort 执行 {@code CREATE EXTENSION IF NOT EXISTS vector}
 * 与 {@code CREATE TABLE IF NOT EXISTS}。</p>
 *
 * <p>检索：{@code ORDER BY embedding <=> '[...]' LIMIT n}（余弦距离 {@code <=>}），
 * 距离 ∈ [0,2]，还原为 {@code cosine = 1 - distance}。
 * 过滤：{@link FilterExpression} 经 {@link PgVectorFilterTranslator} 翻译为
 * {@code metadata->>'field' ...} 的 WHERE 片段（有限子集）。</p>
 *
 * <p>协议格式来源：
 * <a href="https://www.postgresql.org/docs/current/protocol-message-formats.html">Message Formats</a>、
 * <a href="https://www.postgresql.org/docs/current/protocol-flow.html">Message Flow</a>、
 * <a href="https://github.com/pgvector/pgvector">pgvector</a>。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class PgVectorStore implements VectorStore {

	/** 默认主机。 */
	public static final String DEFAULT_HOST = "localhost";
	/** 默认端口。 */
	public static final int DEFAULT_PORT = 5432;
	/** 协议版本 3.0（网络字节序）。 */
	private static final int PROTOCOL_V3 = 196608;

	private final String host;
	private final int port;
	private final String database;
	private final String user;
	private final String password;
	private final String table;
	private final int dimension;
	private final boolean autoCreate;
	private final Duration timeout;
	private final PgVectorFilterTranslator filterTranslator = new PgVectorFilterTranslator();

	private PgVectorStore(Builder b) {
		this.host = Assert.notBlank(b.host, "host 不能为空");
		this.port = b.port;
		this.database = Assert.notBlank(b.database, "database 不能为空");
		this.user = Assert.notBlank(b.user, "user 不能为空");
		this.password = b.password;
		this.table = Assert.notBlank(b.table, "table 不能为空");
		this.dimension = b.dimension;
		this.autoCreate = b.autoCreate;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		if (this.autoCreate) {
			createTable();
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
		StringBuilder sb = new StringBuilder("INSERT INTO ")
				.append(ident(table)).append(" (id, text, metadata, embedding) VALUES ");
		boolean first = true;
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('(')
					.append(sqlString(v.id())).append(',')
					.append(sqlString(v.text() == null ? "" : v.text())).append(',')
					.append(sqlJsonb(v.metadata())).append(',')
					.append(vectorLiteral(v.embedding())).append(')');
		}
		if (first) {
			return;
		}
		sb.append(" ON CONFLICT (id) DO UPDATE SET text=EXCLUDED.text,")
				.append(" metadata=EXCLUDED.metadata, embedding=EXCLUDED.embedding");
		query(sb.toString());
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		PgResult r = query("DELETE FROM " + ident(table) + " WHERE id = " + sqlString(id));
		return r.affected() > 0;
	}

	@Override
	public void clear() {
		query("TRUNCATE TABLE " + ident(table));
	}

	@Override
	public int size() {
		PgResult r = query("SELECT COUNT(*) AS c FROM " + ident(table));
		if (!r.rows.isEmpty()) {
			try {
				return Integer.parseInt(r.rows.get(0).getOrDefault("c", "0"));
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
		String where = filterTranslator.translate(filter);
		StringBuilder sql = new StringBuilder("SELECT id, text, metadata, embedding::text AS embedding,")
				.append(" embedding <=> ").append(vec).append(" AS distance FROM ")
				.append(ident(table));
		if (where != null && !where.isBlank()) {
			sql.append(" WHERE ").append(where);
		}
		sql.append(" ORDER BY embedding <=> ").append(vec).append(" LIMIT ").append(topK);
		PgResult r = query(sql.toString());
		List<SimilaritySearchResult> results = new ArrayList<>(r.rows.size());
		for (Map<String, String> row : r.rows) {
			double distance = Double.parseDouble(row.getOrDefault("distance", "0"));
			double score = 1d - distance;
			if (score < minScore) {
				continue;
			}
			String id = row.get("id");
			String text = row.getOrDefault("text", "");
			Map<String, String> metadata = parseMetadata(row.get("metadata"));
			float[] embedding = parseVector(row.get("embedding"));
			results.add(new SimilaritySearchResult(id, embedding, text, metadata, score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * best-effort 建表（含 pgvector 扩展）：已存在时忽略错误。
	 *
	 * @return 是否执行
	 */
	public boolean createTable() {
		if (dimension <= 0) {
			throw new AiException("pgvector 建表需要 dimension，请通过 Builder.dimension 设置");
		}
		query("CREATE EXTENSION IF NOT EXISTS vector");
		query("CREATE TABLE IF NOT EXISTS " + ident(table) + " ("
				+ "id text PRIMARY KEY, "
				+ "text text, "
				+ "metadata jsonb, "
				+ "embedding vector(" + dimension + "))");
		return true;
	}

	// ===== SQL 字面量辅助（simple query 无参数化，用转义内联） =====

	private static String ident(String name) {
		return '"' + name.replace("\"", "\"\"") + '"';
	}

	private static String sqlString(String s) {
		return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
	}

	private static String sqlJsonb(Map<String, String> metadata) {
		JsonObject o = Json.object();
		for (Map.Entry<String, String> e : metadata.entrySet()) {
			o.set(e.getKey(), e.getValue());
		}
		return "'" + o.toString().replace("'", "''") + "'::jsonb";
	}

	private static String vectorLiteral(float[] v) {
		StringBuilder sb = new StringBuilder('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(v[i]);
		}
		return "'" + sb.append(']').append("'::vector").toString();
	}

	private static float[] parseVector(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String inner = text.trim();
		if (inner.startsWith("[")) {
			inner = inner.substring(1);
		}
		if (inner.endsWith("]")) {
			inner = inner.substring(0, inner.length() - 1);
		}
		String[] parts = inner.split(",");
		float[] out = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			out[i] = Float.parseFloat(parts[i].trim());
		}
		return out;
	}

	private static Map<String, String> parseMetadata(String json) {
		Map<String, String> map = new LinkedHashMap<>();
		if (json == null || json.isBlank() || "null".equals(json)) {
			return map;
		}
		try {
			JsonObject o = (JsonObject) Json.parse(json);
			for (String k : o.keySet()) {
				if (o.get(k) != null && o.get(k).isString()) {
					map.put(k, o.get(k).getAsString());
				}
			}
		} catch (RuntimeException ignored) {
			// 非 JSON 时按空元数据返回，不令检索失败。
		}
		return map;
	}

	// ===== PostgreSQL 简单查询协议（最小子集） =====

	/**
	 * 执行一条 SQL（新建短连接：Startup -> 认证 -> Query -> 读结果 -> 关闭）。
	 *
	 * @param sql SQL 语句
	 * @return 结果集（含行列与命令 tag）
	 */
	private PgResult query(String sql) {
		int timeoutMs = (int) timeout.toMillis();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMs);
			socket.setSoTimeout(timeoutMs);
			OutputStream out = socket.getOutputStream();
			DataInputStream in = new DataInputStream(socket.getInputStream());
			sendStartup(out);
			authenticate(in, out);
			sendQuery(out, sql);
			return readResult(in);
		} catch (IOException e) {
			throw new AiException("pgvector 协议调用失败: " + host + ":" + port, e);
		}
	}

	private void sendStartup(OutputStream out) throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes(int32(PROTOCOL_V3));
		writeCString(body, "user");
		writeCString(body, user);
		writeCString(body, "database");
		writeCString(body, database);
		body.write(0); // 参数区结束
		out.write(int32(4 + body.size()));
		out.write(body.toByteArray());
		out.flush();
	}

	private void authenticate(DataInputStream in, OutputStream out) throws IOException {
		while (true) {
			int type = in.read();
			if (type == -1) {
				throw new AiException("pgvector 连接在认证阶段被关闭");
			}
			int len = in.readInt(); // 含自身 4 字节
			byte[] payload = readN(in, len - 4);
			ByteBuffer bb = wrap(payload);
			switch (type) {
				case 'R' -> {
					int code = bb.getInt();
					if (code == 3) {
						// AuthenticationCleartextPassword：发送明文密码，继续等待 AuthenticationOk
						ByteArrayOutputStream pw = new ByteArrayOutputStream();
						writeCString(pw, password == null ? "" : password);
						out.write('p');
						out.write(int32(4 + pw.size()));
						out.write(pw.toByteArray());
						out.flush();
					} else if (code != 0) {
						// 非 AuthenticationOk：md5(5)/sasl(10) 等不支持
						throw new AiException("pgvector 不支持的认证方式(code=" + code
								+ ")：仅支持 trust/明文密码，md5/scram-sha-256 需服务端调整 pg_hba.conf");
					}
					// code==0 为 AuthenticationOk：继续 drain ParameterStatus/BackendKeyData/ReadyForQuery
				}
				case 'E' -> throw new AiException("pgvector 错误: " + parseError(payload));
				case 'S', 'K' -> {
					// ParameterStatus / BackendKeyData：忽略
				}
				case 'Z' -> {
					return; // 直接就绪（无认证）
				}
				default -> {
					// 其余启动期消息忽略
				}
			}
		}
	}

	private void sendQuery(OutputStream out, String sql) throws IOException {
		byte[] q = sql.getBytes(StandardCharsets.UTF_8);
		out.write('Q');
		out.write(int32(4 + q.length + 1));
		out.write(q);
		out.write(0);
		out.flush();
	}

	private PgResult readResult(DataInputStream in) throws IOException {
		PgResult result = new PgResult();
		while (true) {
			int type = in.read();
			if (type == -1) {
				throw new AiException("pgvector 连接在查询阶段被关闭");
			}
			int len = in.readInt();
			byte[] payload = readN(in, len - 4);
			ByteBuffer bb = wrap(payload);
			switch (type) {
				case 'T' -> result.columns = readRowDescription(bb);
				case 'D' -> result.rows.add(readDataRow(bb, result.columns));
				case 'C' -> result.tag = readCString(bb);
				case 'E' -> throw new AiException("pgvector SQL 错误: " + parseError(payload));
				case 'Z' -> {
					return result;
				}
				default -> {
					// NoticeResponse(N) / EmptyQuery(I) 等忽略
				}
			}
		}
	}

	private static List<String> readRowDescription(ByteBuffer bb) {
		int n = bb.getShort();
		List<String> cols = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			cols.add(readCString(bb));
			bb.position(bb.position() + 18); // tableOid(4)+attrIdx(2)+typeOid(4)+typeLen(2)+typeMod(4)+fmt(2)
		}
		return cols;
	}

	private static Map<String, String> readDataRow(ByteBuffer bb, List<String> columns) {
		int n = bb.getShort();
		Map<String, String> row = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			int len = bb.getInt();
			String value = null;
			if (len >= 0) {
				byte[] buf = new byte[len];
				bb.get(buf);
				value = new String(buf, StandardCharsets.UTF_8);
			}
			if (columns != null && i < columns.size()) {
				row.put(columns.get(i), value);
			}
		}
		return row;
	}

	private static String parseError(byte[] payload) {
		ByteBuffer bb = wrap(payload);
		StringBuilder sb = new StringBuilder();
		while (bb.hasRemaining()) {
			byte fieldType = bb.get();
			if (fieldType == 0) {
				break;
			}
			String msg = readCString(bb);
			if (fieldType == 'M') {
				sb.append(msg);
			} else if (fieldType == 'S' && sb.length() == 0) {
				sb.append('[').append(msg).append("] ");
			}
		}
		return sb.toString();
	}

	private static ByteBuffer wrap(byte[] payload) {
		return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
	}

	private static byte[] readN(DataInputStream in, int n) throws IOException {
		byte[] buf = new byte[n];
		in.readFully(buf);
		return buf;
	}

	private static byte[] int32(int v) {
		return new byte[] { (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v };
	}

	private static void writeCString(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
		out.write(0);
	}

	private static String readCString(ByteBuffer bb) {
		int start = bb.position();
		int end = start;
		while (bb.hasRemaining() && bb.get(end) != 0) {
			end++;
		}
		byte[] buf = new byte[end - start];
		bb.get(buf);
		bb.get(); // 跳过 NUL
		return new String(buf, StandardCharsets.UTF_8);
	}

	/** 简单查询结果集。 */
	private static final class PgResult {
		private List<String> columns;
		private final List<Map<String, String>> rows = new ArrayList<>();
		private String tag = "";

		int affected() {
			if (tag == null) {
				return 0;
			}
			String[] parts = tag.trim().split("\\s+");
			try {
				return Integer.parseInt(parts[parts.length - 1]);
			} catch (NumberFormatException e) {
				return 0;
			}
		}
	}

	/**
	 * {@link PgVectorStore} 构建器。
	 */
	public static final class Builder {

		private String host = DEFAULT_HOST;
		private int port = DEFAULT_PORT;
		private String database;
		private String user;
		private String password;
		private String table;
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
		 * 设置登录用户名（必需）。
		 *
		 * @param user 用户名
		 * @return this
		 */
		public Builder user(String user) {
			this.user = user;
			return this;
		}

		/**
		 * 设置登录密码（明文认证时必需）。
		 *
		 * @param password 密码
		 * @return this
		 */
		public Builder password(String password) {
			this.password = password;
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
		 * 是否构造时自动建扩展与表，默认 false。
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
		 * @return PgVectorStore
		 */
		public PgVectorStore build() {
			return new PgVectorStore(this);
		}
	}
}
