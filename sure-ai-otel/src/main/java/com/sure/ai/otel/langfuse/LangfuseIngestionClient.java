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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 JDK {@link HttpClient} 的 Langfuse 原生 Ingestion HTTP 客户端。
 *
 * <p><b>零第三方依赖</b>：仅用 JDK 自带 {@code java.net.http} 与手写 JSON 拼接，不引入 Jackson/Gson，
 * 也不依赖任何 OpenTelemetry 类。请求体为 Langfuse 官方批量信封：</p>
 * <pre>{@code
 * POST {host}/api/public/ingestion
 * Authorization: Basic base64(pk:sk)
 * Content-Type: application/json
 *
 * { "batch": [ { "id": "...", "type": "trace-create", "timestamp": "...", "body": {...} }, ... ] }
 * }</pre>
 *
 * <p><b>无感降级</b>：配置未启用（密钥缺失）时 {@link #send} 直接返回已完成的 future、不发任何请求；
 * 发送异常 / 非 2xx（含 207 Multi-Status 视为接受）仅以 {@link Level#WARNING} 记录并吞咽，
 * 绝不向调用方抛出，避免破坏主业务流程。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class LangfuseIngestionClient implements LangfuseBatchSender {

	private static final Logger LOG = Logger.getLogger(LangfuseIngestionClient.class.getName());

	/** 请求超时（连接 + 读取），上限保护：网络不可达时最多阻塞此时长。 */
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

	private final LangfuseConfig config;
	private final HttpClient httpClient;

	/**
	 * 以配置构造（内部建立共享 {@link HttpClient}）。
	 *
	 * @param config Langfuse 配置；禁用态下为空操作
	 */
	public LangfuseIngestionClient(LangfuseConfig config) {
		this.config = config == null ? LangfuseConfig.disabled() : config;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(REQUEST_TIMEOUT)
			.build();
	}

	/**
	 * 测试/高级用法注入自定义 HttpClient。
	 *
	 * @param config     Langfuse 配置
	 * @param httpClient 自定义客户端
	 */
	LangfuseIngestionClient(LangfuseConfig config, HttpClient httpClient) {
		this.config = config == null ? LangfuseConfig.disabled() : config;
		this.httpClient = httpClient;
	}

	@Override
	public CompletableFuture<Void> send(List<String> events) {
		if (!this.config.isEnabled() || events == null || events.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		String payload = buildBatchPayload(events);
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder()
				.uri(URI.create(this.config.ingestionUrl()))
				.timeout(REQUEST_TIMEOUT)
				.header("Authorization", this.config.basicAuthorization())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "langfuse ingestion request build failed: " + ex.getMessage(), ex);
			return CompletableFuture.completedFuture(null);
		}
		return this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.handle((resp, ex) -> {
				if (ex != null) {
					LOG.log(Level.WARNING,
						"langfuse ingestion send failed (" + events.size() + " events): "
							+ ex.getMessage(),
						ex);
					return null;
				}
				int code = resp.statusCode();
				if (code < 200 || code > 299) {
					LOG.warning("langfuse ingestion non-2xx status=" + code + " (" + events.size()
						+ " events dropped)");
				}
				return null;
			});
	}

	/** 把事件信封列表组装为 {@code {"batch":[...]}}。 */
	private static String buildBatchPayload(List<String> events) {
		StringBuilder sb = new StringBuilder(64 + events.size() * 128);
		sb.append("{\"batch\":[");
		for (int i = 0; i < events.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(events.get(i));
		}
		sb.append("]}");
		return sb.toString();
	}
}
