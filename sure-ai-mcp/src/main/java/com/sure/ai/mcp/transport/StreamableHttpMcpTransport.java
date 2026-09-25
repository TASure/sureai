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

package com.sure.ai.mcp.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.http.SseEvent;
import com.sure.ai.internal.http.SseLineReader;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;

/**
 * Streamable HTTP 传输：JDK {@link HttpClient} POST 单端点。
 *
 * <p>请求头携带 {@code Content-Type: application/json}、
 * {@code Accept: application/json, text/event-stream}；首次响应若返回
 * {@code Mcp-Session-Id} 头则缓存，后续请求原样带回。</p>
 *
 * <p>响应按 Content-Type 分支：{@code application/json} 直接解析为单个 JSON-RPC 响应；
 * {@code text/event-stream} 用 core 的 {@link SseLineReader} 聚合 SSE，取到与请求 id
 * 匹配的那一条事件数据即返回。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class StreamableHttpMcpTransport implements McpTransport {

	private final HttpClient http;
	private final URI endpoint;
	private final Duration timeout;
	private final AtomicReference<String> sessionId = new AtomicReference<>();
	private volatile boolean open = true;

	/**
	 * 全参构造。
	 *
	 * @param endpoint 单端点 URL（如 {@code http://127.0.0.1:8000/mcp}）
	 * @param timeout  单次请求超时
	 */
	public StreamableHttpMcpTransport(String endpoint, Duration timeout) {
		this.endpoint = URI.create(endpoint);
		this.timeout = timeout == null ? LineFrameMcpTransport.DEFAULT_TIMEOUT : timeout;
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}

	@Override
	public McpResponse sendRequest(McpRequest request) {
		HttpRequest.Builder rb = baseRequest();
		rb.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(request.toJson()), StandardCharsets.UTF_8));
		HttpResponse<InputStream> resp = send(rb);
		return decode(request.id(), resp);
	}

	@Override
	public void sendNotification(McpNotification notification) {
		HttpRequest.Builder rb = baseRequest();
		rb.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(notification.toJson()), StandardCharsets.UTF_8));
		HttpResponse<InputStream> resp;
		try {
			resp = httpSend(rb);
		} catch (Exception ex) {
			throw new AiException("MCP 通知发送失败: " + notification.method(), ex);
		}
		// 通知期望 202 Accepted，不解析 body
		ignoreBody(resp);
	}

	/** 构造带通用头的请求 builder。 */
	private HttpRequest.Builder baseRequest() {
		HttpRequest.Builder rb = HttpRequest.newBuilder(this.endpoint)
			.timeout(this.timeout)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream");
		String sid = this.sessionId.get();
		if (sid != null) {
			rb.header("Mcp-Session-Id", sid);
		}
		return rb;
	}

	/** 同步发送并返回 InputStream 响应。 */
	private HttpResponse<InputStream> send(HttpRequest.Builder rb) {
		try {
			HttpResponse<InputStream> resp = httpSend(rb);
			captureSessionId(resp);
			return resp;
		} catch (AiException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new AiException("MCP HTTP 传输失败: " + ex.getMessage(), ex);
		}
	}

	private HttpResponse<InputStream> httpSend(HttpRequest.Builder rb) throws IOException, InterruptedException {
		return this.http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
	}

	/** 从响应头读取并缓存 Mcp-Session-Id。 */
	private void captureSessionId(HttpResponse<InputStream> resp) {
		List<String> values = resp.headers().allValues("Mcp-Session-Id");
		if (!values.isEmpty()) {
			this.sessionId.compareAndSet(null, values.get(0));
		}
	}

	/** 按 Content-Type 解码响应体为 McpResponse。 */
	private McpResponse decode(long expectedId, HttpResponse<InputStream> resp) {
		int status = resp.statusCode();
		String contentType = resp.headers().firstValue("Content-Type").orElse("");
		try {
			if (status / 100 != 2) {
				String body = readAll(resp.body());
				throw new AiException("MCP HTTP 错误 status=" + status + " body=" + body);
			}
			if (contentType.contains("text/event-stream")) {
				return decodeSse(expectedId, resp.body());
			}
			String body = readAll(resp.body());
			JsonElement el = Json.parse(body);
			return McpResponse.fromJson(el.getAsJsonObject());
		} catch (AiException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new AiException("MCP 响应解析失败: " + ex.getMessage(), ex);
		}
	}

	/** 解析 SSE 流，找到 id 匹配的响应帧。 */
	private McpResponse decodeSse(long expectedId, InputStream in) {
		McpResponse[] found = new McpResponse[1];
		SseLineReader.read(in, StandardCharsets.UTF_8, event -> {
			McpResponse r = tryParseResponse(event);
			if (r != null && r.id() != null && r.id() == expectedId) {
				found[0] = r;
			}
		});
		if (found[0] == null) {
			throw new AiException("SSE 流中未找到 id=" + expectedId + " 的响应");
		}
		return found[0];
	}

	/** 尝试把 SSE 事件数据解析为响应帧，失败返回 null。 */
	private static McpResponse tryParseResponse(SseEvent event) {
		try {
			JsonElement el = Json.parse(event.data());
			if (!el.isObject()) {
				return null;
			}
			JsonObject o = el.getAsJsonObject();
			if (o.has("id") && (o.has("result") || o.has("error"))) {
				return McpResponse.fromJson(o);
			}
			return null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static String readAll(InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	private static void ignoreBody(HttpResponse<InputStream> resp) {
		if (resp == null) {
			return;
		}
		try (InputStream in = resp.body()) {
			in.readAllBytes();
		} catch (IOException ex) {
			// 忽略通知响应体读取异常
		}
	}

	@Override
	public void close() {
		this.open = false;
	}

	@Override
	public boolean isOpen() {
		return this.open;
	}
}
