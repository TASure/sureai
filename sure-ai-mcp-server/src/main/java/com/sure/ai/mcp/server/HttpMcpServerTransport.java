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
import java.util.UUID;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Streamable HTTP 服务端传输：基于 JDK 内置 {@link HttpServer}，单端点同时支持
 * {@code application/json} 直返形态。
 *
 * <p>零 Web 框架依赖。POST 请求体为 JSON-RPC 帧，响应体为 JSON-RPC 响应（{@code application/json}）；
 * 通知（无 id）返回 {@code 202 Accepted}。首次握手响应携带 {@code Mcp-Session-Id}，供有状态客户端缓存。</p>
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

	private final int port;
	private final String path;
	private volatile HttpServer server;
	private volatile McpFrameHandler handler;
	private volatile String sessionId;

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
	 * 启动 HTTP 服务。
	 *
	 * @param handler 协议引擎
	 * @throws IOException 绑定端口失败
	 */
	public void start(McpFrameHandler handler) throws IOException {
		this.handler = handler;
		this.sessionId = UUID.randomUUID().toString();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", this.port), 0);
		this.server.createContext(this.path, this::handle);
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

	private void handle(HttpExchange exchange) throws IOException {
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

	private String readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] all = in.readNBytes(MAX_BODY_BYTES + 1);
			if (all.length > MAX_BODY_BYTES) {
				throw new IOException("request body too large");
			}
			return new String(all, StandardCharsets.UTF_8);
		}
	}

	private void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
		exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept, Mcp-Session-Id");
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
