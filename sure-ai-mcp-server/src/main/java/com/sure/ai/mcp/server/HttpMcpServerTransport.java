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

package com.sure.ai.mcp.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.internal.json.JsonPrimitive;
import com.sure.ai.mcp.transport.McpHeaders;

/**
 * Streamable HTTP 服务端传输：基于 JDK 内置 {@link HttpServer}，单端点同时支持
 * {@code application/json} 直返与 {@code text/event-stream}（SSE）响应。
 *
 * <p>零 Web 框架依赖。POST 请求体为 JSON-RPC 帧，响应体为 JSON-RPC 响应（{@code application/json}）；
 * 通知（无 id）返回 {@code 202 Accepted}。</p>
 *
 * <p><b>2026-07-28 无状态扩展面（v2.3.0 收口）</b>：</p>
 * <ul>
 *   <li><b>OAuth 受保护资源元数据</b>：按 RFC 9728 在
 *       {@code /.well-known/oauth-protected-resource[&lt;path&gt;]} 暴露 {@code resource} /
 *       {@code authorization_servers} / {@code scopes_supported}（见 {@link #authorizationServers}）；</li>
 *   <li><b>subscriptions/listen</b>：POST 到该方法时升级为 {@code text/event-stream} 长连接，
 *       首帧发 {@code notifications/subscriptions/acknowledged}，随后发 SSE 注释保活直到客户端断开；</li>
 *   <li><b>标准请求头</b>：请求若携带 {@code Mcp-Method}/{@code Mcp-Name}，本层解码（Base64 哨兵）
 *       后与 body 比对，不一致返回 {@code 400} + JSON-RPC {@code -32020 HeaderMismatch}；
 *       未携带这些头的旧请求一律放行（向后兼容）。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 * HttpMcpServerTransport http = new HttpMcpServerTransport(8080, "/mcp");
 * server.start(http);
 * int port = http.getPort();
 * }</pre>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class HttpMcpServerTransport implements AutoCloseable {

	/** 请求体最大字节数（16MB）。 */
	private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

	/** RFC 9728 受保护资源元数据 well-known 路径前缀。 */
	private static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

	private final int port;
	private final String path;
	private volatile HttpServer server;
	private volatile McpFrameHandler handler;
	private volatile String sessionId;
	private volatile List<String> authorizationServers = List.of();
	private volatile List<String> scopesSupported = List.of();

	/**
	 * 绑定端口与路径（端口 0 表示随机端口）。
	 *
	 * @param port 监听端口，0 为随机
	 * @param path 单端点路径，如 {@code /mcp}
	 */
	public HttpMcpServerTransport(int port, String path) {
		this.port = port;
		this.path = path == null || path.isEmpty() ? "/mcp" : path;
	}

	/**
	 * 声明 OAuth 授权服务器列表（写入受保护资源元数据）。
	 *
	 * @param urls 授权服务器 URL
	 * @return this
	 */
	public HttpMcpServerTransport authorizationServers(String... urls) {
		this.authorizationServers = List.of(urls);
		return this;
	}

	/**
	 * 声明支持的 OAuth scope（写入受保护资源元数据）。
	 *
	 * @param scopes scope 列表
	 * @return this
	 */
	public HttpMcpServerTransport scopesSupported(String... scopes) {
		this.scopesSupported = List.of(scopes);
		return this;
	}

	/**
	 * 启动 HTTP 服务。
	 *
	 * @param handler 协议引擎
	 * @throws IOException 绑定端口失败
	 */
	public void start(McpFrameHandler handler) throws IOException {
		this.handler = handler;
		this.sessionId = UUID.randomUUID().toString();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", this.port), 0);
		this.server.createContext(this.path, this::handlePost);
		// RFC 9728：受保护资源元数据（根路径 + 资源路径两个 well-known 位置）
		this.server.createContext(WELL_KNOWN, this::handleWellKnown);
		this.server.createContext(WELL_KNOWN + this.path, this::handleWellKnown);
		this.server.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "sureai-mcp-server-http");
			t.setDaemon(true);
			return t;
		}));
		this.server.start();
	}

	/**
	 * 实际绑定端口（随机端口启动后取真实值）。
	 *
	 * @return 端口
	 */
	public int getPort() {
		return this.server == null ? this.port : this.server.getAddress().getPort();
	}

	/**
	 * 端点完整 URL。
	 *
	 * @return 形如 {@code http://127.0.0.1:PORT/mcp}
	 */
	public String endpoint() {
		return "http://127.0.0.1:" + getPort() + this.path;
	}

	// ==================== MCP 端点（POST） ====================

	private void handlePost(HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			if ("OPTIONS".equals(method)) {
				reply(exchange, 204, null, null);
				return;
			}
			if (!"POST".equals(method)) {
				reply(exchange, 405, null, null);
				return;
			}
			String body = readBody(exchange);
			// 标准请求头镜像校验（携带才校验，缺省放行旧客户端）
			String headerError = validateHeaders(exchange, body);
			if (headerError != null) {
				reply(exchange, 400, "application/json", headerError);
				return;
			}
			// subscriptions/listen：升级为 SSE 长连接
			if (isSubscriptionListen(body)) {
				handleListen(exchange, body);
				return;
			}
			String resp;
			try {
				resp = this.handler.handle(body);
			} catch (RuntimeException ex) {
				resp = null;
			}
			if (resp == null) {
				reply(exchange, 202, null, null);
			} else {
				reply(exchange, 200, "application/json", resp);
			}
		} finally {
			exchange.close();
		}
	}

	/** 判断正文是否为 {@code subscriptions/listen} 请求。 */
	private static boolean isSubscriptionListen(String body) {
		try {
			JsonElement el = Json.parse(body);
			return el.isObject() && "subscriptions/listen".equals(el.getAsJsonObject().optString("method", null));
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * 标准请求头镜像校验：头存在时须与 body 一致，否则 400 + -32020 HeaderMismatch。
	 *
	 * @return 不一致时的 JSON-RPC 错误帧；一致或头缺失时返回 {@code null}（放行）
	 */
	private String validateHeaders(HttpExchange exchange, String body) {
		String mcpMethod = exchange.getRequestHeaders().getFirst(McpHeaders.HEADER_MCP_METHOD);
		String mcpName = exchange.getRequestHeaders().getFirst(McpHeaders.HEADER_MCP_NAME);
		if (mcpMethod == null && mcpName == null) {
			return null;
		}
		JsonObject req;
		try {
			JsonElement el = Json.parse(body);
			req = el.isObject() ? el.getAsJsonObject() : null;
		} catch (RuntimeException ex) {
			req = null;
		}
		if (req == null) {
			return null;
		}
		String bodyMethod = req.optString("method", null);
		if (mcpMethod != null && !mcpMethod.equals(bodyMethod)) {
			return headerMismatch(req, "Mcp-Method header '" + mcpMethod + "' != body method '" + bodyMethod + "'");
		}
		if (mcpName != null) {
			JsonObject params = req.has("params") && req.get("params").isObject()
				? req.get("params").getAsJsonObject() : null;
			String expected = McpHeaders.mcpNameFor(bodyMethod, params);
			if (expected != null && !McpHeaders.decode(mcpName).equals(expected)) {
				return headerMismatch(req, "Mcp-Name header does not match body value");
			}
		}
		return null;
	}

	/** 构造 -32020 HeaderMismatch JSON-RPC 错误帧。 */
	private static String headerMismatch(JsonObject req, String message) {
		JsonObject resp = Json.object();
		resp.put("jsonrpc", "2.0");
		resp.set("id", req.has("id") ? req.get("id") : JsonPrimitive.jsonNull());
		JsonObject err = Json.object();
		err.put("code", -32020L);
		err.put("message", "HeaderMismatch: " + message);
		resp.set("error", err);
		return Json.stringify(resp);
	}

	/**
	 * {@code subscriptions/listen}：以 {@code text/event-stream} 应答，首帧即
	 * {@code notifications/subscriptions/acknowledged}（带 subscriptionId）。
	 *
	 * <p>本批无运行期列表变更源（工具在启动期静态注册），故在发出 ack 后随附一条 graceful-close
	 * 完成结果即结束流；后续变更推送（{@code notifications/tools/list_changed} 等）需要变更源接入后
	 * 再扩展为真正的长连接。</p>
	 */
	private void handleListen(HttpExchange exchange, String body) throws IOException {
		JsonObject req = Json.parse(body).getAsJsonObject();
		JsonElement id = req.has("id") ? req.get("id") : JsonPrimitive.jsonNull();
		JsonObject params = req.has("params") && req.get("params").isObject()
			? req.get("params").getAsJsonObject() : Json.object();
		JsonObject accepted = McpSubscriptions.accepted(params);
		String ack = Json.stringify(McpSubscriptions.ackNotification(id, accepted));
		// graceful-close 完成结果（带 subscriptionId），标识订阅正常结束
		JsonObject done = Json.object();
		done.put("resultType", "complete");
		JsonObject doneMeta = Json.object();
		doneMeta.set(McpSubscriptions.META_SUBSCRIPTION_ID, id);
		done.set("_meta", doneMeta);

		String sse = "event: " + McpSubscriptions.ACK_METHOD + "\ndata: " + ack + "\n\n"
			+ "event: message\ndata: " + Json.stringify(done) + "\n\n";
		byte[] frame = sse.getBytes(StandardCharsets.UTF_8);

		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
		applyCors(exchange);
		if (this.sessionId != null) {
			exchange.getResponseHeaders().set("Mcp-Session-Id", this.sessionId);
		}
		exchange.sendResponseHeaders(200, frame.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(frame);
			out.flush();
		}
	}

	// ==================== OAuth 受保护资源元数据（RFC 9728） ====================

	private void handleWellKnown(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
				reply(exchange, 405, null, null);
				return;
			}
			JsonObject meta = Json.object();
			meta.put("resource", this.endpoint());
			JsonArray servers = Json.array();
			for (String s : this.authorizationServers) {
				servers.add(s);
			}
			meta.set("authorization_servers", servers);
			JsonArray scopes = Json.array();
			for (String s : this.scopesSupported) {
				scopes.add(s);
			}
			meta.set("scopes_supported", scopes);
			reply(exchange, 200, "application/json", Json.stringify(meta));
		} finally {
			exchange.close();
		}
	}

	// ==================== 公共 ====================

	private String readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] all = in.readNBytes(MAX_BODY_BYTES + 1);
			if (all.length > MAX_BODY_BYTES) {
				throw new IOException("request body too large");
			}
			return new String(all, StandardCharsets.UTF_8);
		}
	}

	/** 写 CORS 头。 */
	private void applyCors(HttpExchange exchange) {
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
		exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
			"Content-Type, Accept, Mcp-Session-Id, Mcp-Method, Mcp-Name, MCP-Protocol-Version, Mcp-Param-*");
	}

	private void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		applyCors(exchange);
		if (this.sessionId != null) {
			exchange.getResponseHeaders().set("Mcp-Session-Id", this.sessionId);
		}
		if (contentType != null) {
			exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
		}
		byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	@Override
	public void close() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}
}
