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

package com.sure.ai.otel.langfuse;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sure.ai.client.observability.MetricsCollector;

/**
 * 把 sureai {@link MetricsCollector} 生命周期映射为 Langfuse 原生 Ingestion 事件。
 *
 * <p><b>最小可用映射</b>：一次逻辑 LLM 调用 = 一个 {@code trace} + 一个 {@code generation} 观察。
 * 这是 Langfuse 作为 LLM 观测平台的基本单元（见
 * <a href="https://langfuse.com/docs/observability/features/observation-types">Observation Types</a>）：</p>
 * <ul>
 *   <li>{@link #onRequestStart} —— 新建 traceId / generationId，缓冲 {@code trace-create} 与
 *       {@code generation-create}（含 {@code startTime}）；</li>
 *   <li>{@link #onTokenUsage} —— 暂存 model 与 prompt/completion/total token，供终态写入
 *       {@code generation-update} 的 {@code usage}；</li>
 *   <li>{@link #onRetry} —— 追加一个 {@code event-create}（重试事件，挂在该 trace/generation 下）；</li>
 *   <li>{@link #onRequestSuccess} —— 追加 {@code generation-update}（{@code level=DEFAULT}、
 *       {@code endTime}、{@code model}、{@code usage}），整批 POST；</li>
 *   <li>{@link #onRequestFailure} —— 追加 {@code generation-update}（{@code level=ERROR}、
 *       {@code statusMessage}），整批 POST。</li>
 * </ul>
 *
 * <p>同一逻辑调用的所有事件被组装成一个 {@code batch} 数组、单次 POST 到
 * {@code /api/public/ingestion}。start 与终态由 {@code RetryExecutor} 保证成对（请求线程同步回调），
 * 中间态用 {@link ThreadLocal} 串联；终态一定 {@code remove()}，防止线程池复用泄漏。</p>
 *
 * <p><b>无感降级</b>：未配置密钥时全部方法空操作；发送失败由 {@link LangfuseIngestionClient}
 * 记录 warning 并吞咽，本类自身任何异常也不会向上抛出。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class LangfuseMetricsCollector implements MetricsCollector {

	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

	private static final Logger LOG = Logger.getLogger(LangfuseMetricsCollector.class.getName());

	private final boolean enabled;
	private final LangfuseBatchSender sender;
	private final String environment;

	private final ThreadLocal<InFlight> inFlight = new ThreadLocal<>();

	/**
	 * 以配置构造（内部建立真实 HTTP 发送器）。
	 *
	 * @param config Langfuse 配置；禁用态下为空操作
	 */
	public LangfuseMetricsCollector(LangfuseConfig config) {
		this(config != null && config.isEnabled(),
			config == null || !config.isEnabled()
				? LangfuseBatchSender.noop()
				: new LangfuseIngestionClient(config),
			config == null ? null : config.environment());
	}

	/**
	 * 测试/高级构造：注入启用标志与发送桩。
	 *
	 * @param enabled     是否启用
	 * @param sender      批次发送器
	 * @param environment 环境标签；可空
	 */
	LangfuseMetricsCollector(boolean enabled, LangfuseBatchSender sender, String environment) {
		this.enabled = enabled;
		this.sender = sender;
		this.environment = environment;
	}

	@Override
	public void onRequestStart(String path) {
		if (!this.enabled) {
			return;
		}
		// 防御：上一次未正常收尾（如异常路径），先按错误收尾，避免 ThreadLocal 串号
		InFlight leaked = this.inFlight.get();
		if (leaked != null) {
			finish(leaked, "ERROR", "previous call unfinished", true);
		}
		long now = System.currentTimeMillis();
		String traceId = newTraceId();
		String genId = newObservationId();
		List<String> batch = new ArrayList<>(4);
		batch.add(traceCreate(traceId, path, now, this.environment));
		batch.add(generationCreate(genId, traceId, path, now));
		this.inFlight.set(new InFlight(traceId, genId, path, now, batch));
	}

	@Override
	public void onRequestSuccess(String path, int httpStatus, long durationMs) {
		if (!this.enabled) {
			return;
		}
		InFlight state = this.inFlight.get();
		if (state == null) {
			return;
		}
		finish(state, "DEFAULT", "HTTP " + httpStatus, false);
	}

	@Override
	public void onRequestFailure(String path, int httpStatus, Exception exception, long durationMs) {
		if (!this.enabled) {
			return;
		}
		InFlight state = this.inFlight.get();
		if (state == null) {
			return;
		}
		finish(state, "ERROR", errorMessage(httpStatus, exception), true);
	}

	@Override
	public void onRetry(String path, int attempt, int httpStatus) {
		if (!this.enabled) {
			return;
		}
		InFlight state = this.inFlight.get();
		if (state == null) {
			return;
		}
		state.batch.add(retryEvent(state, attempt, httpStatus));
	}

	@Override
	public void onTokenUsage(String model, long promptTokens, long completionTokens,
			long totalTokens) {
		if (!this.enabled) {
			return;
		}
		InFlight state = this.inFlight.get();
		if (state == null) {
			return;
		}
		state.model = model;
		state.promptTokens = promptTokens;
		state.completionTokens = completionTokens;
		state.totalTokens = totalTokens;
		state.usageSeen = true;
	}

	/** 追加终态 generation-update、发送整批、清理 ThreadLocal。 */
	private void finish(InFlight state, String level, String statusMessage, boolean error) {
		try {
			state.batch.add(generationUpdate(state, level, statusMessage));
			this.sender.send(state.batch);
		} catch (RuntimeException ex) {
			// 双保险：发送层已吞异常，这里再兜一层，绝不影响主流程
			LOG.log(Level.WARNING, "langfuse finish error: " + ex.getMessage(), ex);
		} finally {
			this.inFlight.remove();
		}
	}

	// ---------- 事件信封构造 ----------

	private static String traceCreate(String traceId, String name, long epochMs, String env) {
		StringBuilder body = new StringBuilder(128);
		body.append("{\"id\":").append(json(traceId))
			.append(",\"name\":").append(json(name));
		appendMetadata(body, "trace");
		if (env != null) {
			body.append(",\"environment\":").append(json(env));
		}
		body.append('}');
		return envelope("trace-create", epochMs, body.toString());
	}

	private static String generationCreate(String genId, String traceId, String name, long epochMs) {
		StringBuilder body = new StringBuilder(160);
		body.append("{\"id\":").append(json(genId))
			.append(",\"traceId\":").append(json(traceId))
			.append(",\"name\":").append(json(name))
			.append(",\"startTime\":").append(json(iso(epochMs)));
		appendMetadata(body, "generation");
		body.append('}');
		return envelope("generation-create", epochMs, body.toString());
	}

	private String generationUpdate(InFlight state, String level, String statusMessage) {
		long endMs = System.currentTimeMillis();
		StringBuilder body = new StringBuilder(200);
		body.append("{\"id\":").append(json(state.genId))
			.append(",\"traceId\":").append(json(state.traceId))
			.append(",\"endTime\":").append(json(iso(endMs)))
			.append(",\"level\":").append(json(level))
			.append(",\"statusMessage\":").append(json(statusMessage));
		if (state.model != null && !state.model.isBlank()) {
			body.append(",\"model\":").append(json(state.model));
		}
		if (state.usageSeen) {
			body.append(",\"usage\":{\"input\":").append(state.promptTokens)
				.append(",\"output\":").append(state.completionTokens)
				.append(",\"total\":").append(state.totalTokens).append('}');
		}
		body.append('}');
		return envelope("generation-update", endMs, body.toString());
	}

	private static String retryEvent(InFlight state, int attempt, int httpStatus) {
		long now = System.currentTimeMillis();
		String body = "{\"id\":" + json(newObservationId())
			+ ",\"traceId\":" + json(state.traceId)
			+ ",\"parentObservationId\":" + json(state.genId)
			+ ",\"name\":\"retry\""
			+ ",\"startTime\":" + json(iso(now))
			+ ",\"metadata\":{\"attempt\":" + attempt + ",\"httpStatus\":" + httpStatus + "}}";
		return envelope("event-create", now, body);
	}

	/** 外层信封：{@code {"id":事件uuid,"type":...,"timestamp":...,"body":{...}}}。 */
	private static String envelope(String type, long epochMs, String bodyJson) {
		return "{\"id\":" + json(UUID.randomUUID().toString())
			+ ",\"type\":\"" + type + "\""
			+ ",\"timestamp\":" + json(iso(epochMs))
			+ ",\"body\":" + bodyJson + "}";
	}

	private static void appendMetadata(StringBuilder body, String kind) {
		body.append(",\"metadata\":{\"source\":\"sureai\",\"kind\":\"").append(kind).append("\"}");
	}

	// ---------- 工具 ----------

	private static String errorMessage(int httpStatus, Exception exception) {
		if (exception != null) {
			String msg = exception.getMessage();
			String base = exception.getClass().getSimpleName();
			return (msg == null || msg.isBlank()) ? base : base + ": " + msg;
		}
		return httpStatus > 0 ? "HTTP " + httpStatus : "request failed";
	}

	private static String iso(long epochMs) {
		return ISO.format(Instant.ofEpochMilli(epochMs));
	}

	private static String newTraceId() {
		return "trace-" + UUID.randomUUID();
	}

	private static String newObservationId() {
		return "obs-" + UUID.randomUUID();
	}

	/** 转义为 JSON 字符串字面量（含两端引号）。 */
	static String json(String s) {
		if (s == null) {
			return "\"\"";
		}
		StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.append('"').toString();
	}

	/** 在途调用状态（每线程一份）。 */
	private static final class InFlight {
		private final String traceId;
		private final String genId;
		@SuppressWarnings("unused")
		private final String path;
		@SuppressWarnings("unused")
		private final long startEpochMs;
		private final List<String> batch;
		private String model;
		private long promptTokens;
		private long completionTokens;
		private long totalTokens;
		private boolean usageSeen;

		InFlight(String traceId, String genId, String path, long startEpochMs, List<String> batch) {
			this.traceId = traceId;
			this.genId = genId;
			this.path = path;
			this.startEpochMs = startEpochMs;
			this.batch = batch;
		}
	}
}
