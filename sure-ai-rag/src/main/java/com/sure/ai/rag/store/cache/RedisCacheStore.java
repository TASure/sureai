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

package com.sure.ai.rag.store.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sure.ai.client.cache.CacheStore;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.GroundingSource;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.rag.store.resp.RespCodec;
import com.sure.tool.lang.Assert;

/**
 * 基于 Redis（RESP2 over JDK {@link Socket}）的外部对话缓存，实现 core 的 {@link CacheStore} SPI。
 *
 * <p><b>零第三方依赖</b>：复用 v2.4.0 抽取的共享 {@link RespCodec}，不引入 Lettuce/Jedis；
 * Socket 为 JDK 自带。语义与内置 {@code LruCacheStore} 对齐：</p>
 * <ul>
 *   <li>{@code get} 返回 {@code null} 表示未命中或已过期（Redis 键不存在 / {@code $-1}）；</li>
 *   <li>{@code put} 的 {@code ttlMillis <= 0} 时使用 store 默认 TTL（默认 5 分钟，与 LRU 一致），
 *       以 {@code SET key value PX ttl} 落地；</li>
 *   <li>{@code remove} 用 {@code DEL}；{@code clear} 用 {@code SCAN} 按 key 前缀迭代收集后批量
 *       {@code DEL}（避免阻塞式 {@code KEYS}）。</li>
 * </ul>
 *
 * <p><b>序列化</b>：值直接存 {@link ChatResponse#rawJson()}（OpenAI 兼容原始响应体），
 * 取回时按响应侧字段（id/model/choices/message.content/reasoning_content/tool_calls/usage）
 * 还原为 {@link ChatResponse}，<b>无损且与首次解析路径同构</b>。</p>
 *
 * <p><b>连接管理</b>：复用单条 Socket（{@code synchronized} 串行化），一次 IO 失败后关闭并自动
 * 重连重试一次。<b>异常语义</b>：Redis 不可达或返回错误回复（{@code -ERR}）时抛
 * {@link AiException}（fail-fast），由上层决定降级，不在缓存层吞错。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class RedisCacheStore implements CacheStore, AutoCloseable {

	/** 默认主机。 */
	public static final String DEFAULT_HOST = "localhost";
	/** 默认端口。 */
	public static final int DEFAULT_PORT = 6379;
	/** 默认 key 前缀。 */
	public static final String DEFAULT_KEY_PREFIX = "sureai:cache:";
	/** 默认 TTL：5 分钟（与 LruCacheStore.DEFAULT_TTL_MILLIS 对齐）。 */
	public static final long DEFAULT_TTL_MILLIS = 5L * 60L * 1000L;
	/** 默认连接/读取超时。 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
	/** SCAN 批量大小。 */
	private static final int SCAN_COUNT = 200;

	private final String host;
	private final int port;
	private final String password;
	private final String keyPrefix;
	private final long defaultTtlMillis;
	private final int timeoutMs;

	private final Object lock = new Object();
	private Socket socket;
	private OutputStream out;
	private InputStream in;

	private RedisCacheStore(Builder b) {
		this.host = Assert.notBlank(b.host, "host 不能为空");
		this.port = b.port;
		this.password = b.password;
		this.keyPrefix = Assert.notBlank(b.keyPrefix, "keyPrefix 不能为空");
		Assert.isTrue(b.defaultTtlMillis > 0, "defaultTtlMillis 必须为正: " + b.defaultTtlMillis);
		this.defaultTtlMillis = b.defaultTtlMillis;
		Duration t = b.timeout == null ? DEFAULT_TIMEOUT : b.timeout;
		this.timeoutMs = (int) t.toMillis();
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
	public ChatResponse get(String key) {
		Assert.notBlank(key, "key 不能为空");
		Object resp = execute(List.of("GET", prefixed(key)));
		if (resp == null) {
			return null;
		}
		return decode(String.valueOf(resp));
	}

	@Override
	public void put(String key, ChatResponse response, long ttlMillis) {
		Assert.notBlank(key, "key 不能为空");
		Assert.notNull(response, "response 不能为 null");
		String rawJson = response.rawJson();
		Assert.notNull(rawJson, "ChatResponse.rawJson() 为空，无法序列化到 Redis 缓存");
		long ttl = ttlMillis > 0 ? ttlMillis : this.defaultTtlMillis;
		execute(List.of("SET", prefixed(key), rawJson, "PX", String.valueOf(ttl)));
	}

	@Override
	public void remove(String key) {
		Assert.notBlank(key, "key 不能为空");
		execute(List.of("DEL", prefixed(key)));
	}

	@Override
	public void clear() {
		List<String> keys = new ArrayList<>();
		String cursor = "0";
		do {
			Object resp = execute(List.of("SCAN", cursor, "MATCH", this.keyPrefix + "*",
				"COUNT", String.valueOf(SCAN_COUNT)));
			if (!(resp instanceof List<?> arr) || arr.size() < 2) {
				break;
			}
			cursor = String.valueOf(arr.get(0));
			if (arr.get(1) instanceof List<?> batch) {
				for (Object k : batch) {
					keys.add(String.valueOf(k));
				}
			}
		} while (!"0".equals(cursor));
		if (!keys.isEmpty()) {
			List<Object> cmd = new ArrayList<>(keys.size() + 1);
			cmd.add("DEL");
			cmd.addAll(keys);
			execute(cmd);
		}
	}

	/**
	 * 关闭底层 Socket 连接。
	 */
	@Override
	public void close() {
		synchronized (this.lock) {
			closeSocket();
		}
	}

	private String prefixed(String key) {
		return this.keyPrefix + key;
	}

	/**
	 * 串行执行一条命令：确保已连接 -> 写 -> 读；传输层失败（IO 异常或连接被对端关闭）
	 * 关闭并自动重连重试一次。应用错误回复（{@code -ERR ...}，消息以 "Redis 错误" 开头）
	 * 属于业务语义，不重试、直接抛出。
	 */
	private Object execute(List<Object> command) {
		synchronized (this.lock) {
			try {
				ensureConnected();
				return writeAndRead(command);
			} catch (IOException first) {
				closeSocket();
			} catch (AiException ae) {
				if (isAppError(ae)) {
					throw ae;
				}
				closeSocket();
			}
			// 重连后重试一次
			try {
				ensureConnected();
				return writeAndRead(command);
			} catch (IOException second) {
				throw new AiException("Redis RESP 调用失败: " + host + ":" + port, second);
			} catch (AiException ae2) {
				if (isAppError(ae2)) {
					throw ae2;
				}
				throw new AiException("Redis RESP 调用失败: " + host + ":" + port, ae2);
			}
		}
	}

	/** 应用错误回复（服务端 {@code -ERR}）：不可重试，直接 fail-fast。 */
	private static boolean isAppError(AiException ae) {
		return ae.getMessage() != null && ae.getMessage().startsWith("Redis 错误");
	}

	private Object writeAndRead(List<Object> command) throws IOException {
		this.out.write(RespCodec.encode(command));
		this.out.flush();
		return RespCodec.readReply(this.in);
	}

	private void ensureConnected() throws IOException {
		if (this.socket != null && !this.socket.isClosed() && this.in != null && this.out != null) {
			return;
		}
		Socket s = new Socket();
		s.connect(new InetSocketAddress(this.host, this.port), this.timeoutMs);
		s.setSoTimeout(this.timeoutMs);
		this.out = s.getOutputStream();
		this.in = s.getInputStream();
		this.socket = s;
		if (this.password != null && !this.password.isBlank()) {
			this.out.write(RespCodec.encode(List.of("AUTH", this.password)));
			this.out.flush();
			RespCodec.readReply(this.in);
		}
	}

	private void closeSocket() {
		try {
			if (this.socket != null) {
				this.socket.close();
			}
		} catch (IOException ignored) {
			// 关闭失败忽略
		} finally {
			this.socket = null;
			this.out = null;
			this.in = null;
		}
	}

	/**
	 * 把存储的原始响应体还原为 {@link ChatResponse}（响应侧字段，与首次解析同构）。
	 */
	private static ChatResponse decode(String rawJson) {
		try {
			JsonObject resp = Json.parse(rawJson).getAsJsonObject();
			String id = resp.optString("id", null);
			String model = resp.optString("model", null);
			List<Choice> choices = new ArrayList<>();
			List<GroundingSource> grounding = new ArrayList<>();
			if (resp.has("choices")) {
				JsonArray arr = resp.getJsonArray("choices");
				for (int i = 0; i < arr.size(); i++) {
					JsonObject c = arr.getJsonObject(i);
					JsonObject msg = c.getJsonObject("message");
					choices.add(Choice.of(c.optInt("index", 0), decodeMessage(msg),
						c.optString("finish_reason", null)));
					collectGrounding(msg, grounding);
				}
			}
			TokenUsage usage = null;
			if (resp.has("usage")) {
				JsonObject u = resp.getJsonObject("usage");
				usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
					u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
			}
			return ChatResponse.of(id, model, choices, usage, grounding, rawJson);
		} catch (AiException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new AiException("Redis 缓存值无法解析为 ChatResponse", e);
		}
	}

	private static ChatMessage decodeMessage(JsonObject msg) {
		Role role = msg.has("role") ? Role.fromValue(msg.getString("role")) : null;
		String content = msg.has("content") && !msg.get("content").isNull()
			? msg.getString("content") : null;
		String reasoning = msg.has("reasoning_content") && !msg.get("reasoning_content").isNull()
			? msg.getString("reasoning_content") : null;
		List<ToolCall> calls = null;
		if (msg.has("tool_calls")) {
			calls = new ArrayList<>();
			JsonArray tc = msg.getJsonArray("tool_calls");
			for (int i = 0; i < tc.size(); i++) {
				JsonObject c = tc.getJsonObject(i);
				JsonObject fn = c.getJsonObject("function");
				calls.add(ToolCall.of(c.optString("id", null), fn.optString("name", null),
					fn.optString("arguments", null)));
			}
		}
		return ChatMessage.of(role, content, null, null, null, calls, reasoning);
	}

	private static void collectGrounding(JsonObject msg, List<GroundingSource> out) {
		if (!msg.has("annotations")) {
			return;
		}
		JsonArray annotations = msg.getJsonArray("annotations");
		for (int i = 0; i < annotations.size(); i++) {
			JsonObject ann = annotations.getJsonObject(i);
			String quoted = ann.optString("quoted_text", null);
			if ("url_citation".equals(ann.optString("type", null)) && ann.has("url_citation")) {
				JsonObject uc = ann.getJsonObject("url_citation");
				out.add(GroundingSource.of(uc.optString("title", null), uc.optString("url", null), quoted));
			} else if (ann.has("url")) {
				out.add(GroundingSource.of(ann.optString("title", null), ann.optString("url", null), quoted));
			}
		}
	}

	/**
	 * {@link RedisCacheStore} 构建器。
	 */
	public static final class Builder {

		private String host = DEFAULT_HOST;
		private int port = DEFAULT_PORT;
		private String password;
		private String keyPrefix = DEFAULT_KEY_PREFIX;
		private long defaultTtlMillis = DEFAULT_TTL_MILLIS;
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
		 * 设置 key 前缀，默认 {@code sureai:cache:}。
		 *
		 * @param keyPrefix key 前缀
		 * @return this
		 */
		public Builder keyPrefix(String keyPrefix) {
			this.keyPrefix = keyPrefix;
			return this;
		}

		/**
		 * 设置默认 TTL（{@code ttlMillis<=0} 时使用），默认 5 分钟。
		 *
		 * @param defaultTtlMillis 默认存活毫秒数（&gt;0）
		 * @return this
		 */
		public Builder defaultTtlMillis(long defaultTtlMillis) {
			this.defaultTtlMillis = defaultTtlMillis;
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
		 * @return RedisCacheStore
		 */
		public RedisCacheStore build() {
			return new RedisCacheStore(this);
		}
	}
}
