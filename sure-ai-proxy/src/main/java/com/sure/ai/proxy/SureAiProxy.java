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

package com.sure.ai.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * OpenAI 兼容 HTTP 代理（LiteLLM proxy 模式）。
 *
 * <p>基于 JDK 内置 {@link HttpServer}（零新依赖），把 {@link GatewayClient} 的多供应商路由、
 * 能力过滤、加权与故障转移能力以 OpenAI 兼容 API 暴露。任何 OpenAI SDK 只需把
 * {@code baseUrl} 指向 {@code http://host:port/v1} 即可使用全部已注册平台能力。</p>
 *
 * <p>暴露端点：</p>
 * <ul>
 *   <li>{@code POST /v1/chat/completions} —— 非流式与 SSE 流式（{@code stream:true}）；</li>
 *   <li>{@code GET  /v1/models} —— 列出配置对外暴露的模型；</li>
 *   <li>{@code POST /v1/embeddings} —— 路由到声明 {@link Capability#EMBED} 且实现了
 *       {@link EmbeddingClient} 的已注册客户端（未注册返回 501）。</li>
 * </ul>
 *
 * <p>鉴权：每个请求校验 {@code Authorization: Bearer <virtualKey>}，命中后把虚拟密钥映射出的
 * tenantId 写入 {@code ChatRequest.extra["tenantId"]}，为多租户配额预留。</p>
 *
 * <p>本类不硬依赖任何平台模块——平台 Client 由使用方注册到 {@link ClientRegistry}。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class SureAiProxy {

	/** SSE 结束标记。 */
	private static final String SSE_DONE = "[DONE]";

	/** 网关对话客户端。 */
	private final GatewayClient gateway;

	/** 客户端注册表（可为 null；非 null 时用于 embeddings 路由）。 */
	private final ClientRegistry registry;

	/** 代理配置。 */
	private final ProxyConfig config;

	/** 虚拟密钥鉴权器。 */
	private final VirtualKeyAuth auth;

	/** HTTP 服务器。 */
	private HttpServer server;

	/** 请求处理线程池。 */
	private ExecutorService executor;

	/**
	 * 编程式启动（仅网关对话；embeddings 不可用）。
	 *
	 * @param gateway 网关客户端
	 * @param config  代理配置
	 */
	public SureAiProxy(GatewayClient gateway, ProxyConfig config) {
		this(gateway, null, config);
	}

	/**
	 * 编程式启动（带注册表，启用 embeddings 路由）。
	 *
	 * @param gateway  网关客户端
	 * @param registry 客户端注册表
	 * @param config    代理配置
	 */
	public SureAiProxy(GatewayClient gateway, ClientRegistry registry, ProxyConfig config) {
		this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
		this.registry = registry;
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.auth = new VirtualKeyAuth(config.keyToTenant());
	}

	/**
	 * 绑定端口并启动 HTTP 服务（异步监听）。
	 *
	 * @throws IOException 绑定失败
	 */
	public void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(this.config.port()), 0);
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
		this.executor = Executors.newFixedThreadPool(threads);
		this.server.setExecutor(this.executor);
		this.server.createContext("/v1/chat/completions", this::handleChat);
		this.server.createContext("/v1/models", this::handleModels);
		this.server.createContext("/v1/embeddings", this::handleEmbeddings);
		this.server.start();
	}

	/** 停止服务并释放线程池。 */
	public void stop() {
		if (this.server != null) {
			this.server.stop(0);
		}
		if (this.executor != null) {
			this.executor.shutdown();
		}
	}

	/**
	 * 实际绑定端口（配置 0 时为系统分配的临时端口，便于测试）。
	 *
	 * @return 端口
	 */
	public int boundPort() {
		return this.server.getAddress().getPort();
	}

	// ---------------------------------------------------------------------
	// 端点处理
	// ---------------------------------------------------------------------

	/** POST /v1/chat/completions（非流 + SSE 流）。 */
	private void handleChat(HttpExchange exchange) throws IOException {
		String tenant = authenticateOr401(exchange);
		if (tenant == null) {
			return;
		}
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendError(exchange, 405, "Method not allowed", "invalid_request_error", "method_not_allowed");
			return;
		}
		ChatRequest chatReq;
		try {
			JsonObject body = OpenAiProtocol.parseBody(readBody(exchange));
			chatReq = OpenAiProtocol.toCoreChatRequest(body, this.config.defaultModel(), tenant);
		} catch (AiException bad) {
			sendError(exchange, 400, bad.getMessage(), "invalid_request_error", "bad_request");
			return;
		}
		try {
			if (chatReq.stream()) {
				handleStream(exchange, chatReq);
			} else {
				ChatResponse resp = this.gateway.chat(chatReq);
				sendJson(exchange, 200, OpenAiProtocol.chatCompletionJson(resp));
			}
		} catch (AiException upstream) {
			sendError(exchange, 502, upstream.getMessage(), "upstream_error", "gateway_error");
		}
	}

	/** SSE 流式：逐片写 data 事件，结束写 [DONE]。 */
	private void handleStream(HttpExchange exchange, ChatRequest chatReq) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");
		exchange.sendResponseHeaders(200, 0);
		OutputStream out = exchange.getResponseBody();
		try {
			this.gateway.chatStream(chatReq,
					chunk -> writeSse(out, OpenAiProtocol.streamChunkJson(chunk, chatReq.model())));
			writeSse(out, SSE_DONE);
		} catch (AiException upstream) {
			writeSse(out, OpenAiProtocol.errorJson(upstream.getMessage(), "upstream_error", "gateway_error"));
		} finally {
			out.flush();
			out.close();
		}
	}

	/** GET /v1/models。 */
	private void handleModels(HttpExchange exchange) throws IOException {
		String tenant = authenticateOr401(exchange);
		if (tenant == null) {
			return;
		}
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendError(exchange, 405, "Method not allowed", "invalid_request_error", "method_not_allowed");
			return;
		}
		sendJson(exchange, 200, OpenAiProtocol.modelsJson(this.config.exposedModels()));
	}

	/** POST /v1/embeddings（可选，路由到 EMBED 能力客户端）。 */
	private void handleEmbeddings(HttpExchange exchange) throws IOException {
		String tenant = authenticateOr401(exchange);
		if (tenant == null) {
			return;
		}
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendError(exchange, 405, "Method not allowed", "invalid_request_error", "method_not_allowed");
			return;
		}
		EmbeddingRequest embeddingRequest;
		try {
			JsonObject body = OpenAiProtocol.parseBody(readBody(exchange));
			String model = body.optString("model", this.config.defaultModel());
			List<String> input = parseInput(body.get("input"));
			embeddingRequest = new EmbeddingRequest(model, input);
		} catch (AiException bad) {
			sendError(exchange, 400, bad.getMessage(), "invalid_request_error", "bad_request");
			return;
		}
		EmbeddingClient client = findEmbeddingClient();
		if (client == null) {
			sendError(exchange, 501, "No embedding client registered",
					"not_implemented", "no_embedding_client");
			return;
		}
		try {
			EmbeddingResponse resp = client.embed(embeddingRequest);
			sendJson(exchange, 200, OpenAiProtocol.embeddingsJson(resp));
		} catch (AiException upstream) {
			sendError(exchange, 502, upstream.getMessage(), "upstream_error", "gateway_error");
		}
	}

	// ---------------------------------------------------------------------
	// 内部工具
	// ---------------------------------------------------------------------

	/** 鉴权：失败直接写 401 响应并返回 null。 */
	private String authenticateOr401(HttpExchange exchange) throws IOException {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		String tenant = this.auth.authenticate(header);
		if (tenant == null) {
			sendError(exchange, 401, "Invalid API key", "invalid_request_error", "invalid_api_key");
		}
		return tenant;
	}

	/** 从注册表找第一个声明 EMBED 能力且实现 EmbeddingClient 的客户端。 */
	private EmbeddingClient findEmbeddingClient() {
		if (this.registry == null) {
			return null;
		}
		for (AiClient c : this.registry.byCapability(Capability.EMBED)) {
			if (c instanceof EmbeddingClient ec) {
				return ec;
			}
		}
		return null;
	}

	/** OpenAI input 可为字符串或字符串数组。 */
	private static List<String> parseInput(JsonElement inputEl) {
		List<String> out = new ArrayList<>();
		if (inputEl == null || inputEl.isNull()) {
			throw new AiException("'input' is required");
		}
		if (inputEl.isString()) {
			out.add(inputEl.getAsString());
			return out;
		}
		if (inputEl.isArray()) {
			for (JsonElement e : inputEl.getAsJsonArray()) {
				out.add(e.getAsString());
			}
			return out;
		}
		throw new AiException("'input' must be a string or an array of strings");
	}

	/** 读取请求体为 UTF-8 字符串。 */
	private static String readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** 写 JSON 响应。 */
	private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/** 写 OpenAI 错误响应。 */
	private static void sendError(HttpExchange exchange, int status, String message,
			String type, String code) throws IOException {
		sendJson(exchange, status, OpenAiProtocol.errorJson(message, type, code));
	}

	/** 写一条 SSE 事件（data: <payload>\n\n）并立即刷新。 */
	private static void writeSse(OutputStream out, String payload) {
		try {
			out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		} catch (IOException e) {
			throw new IllegalStateException("SSE write failed", e);
		}
	}

	/**
	 * main 入口：从 properties 文件加载配置并启动代理。
	 *
	 * <p>注意：本入口仅注册空 {@link ClientRegistry}，不挂载任何平台 Client——真实使用请通过编程式
	 * 构造器在注册完平台 Client 后再 {@code start()}。配置文件只描述端口/虚拟密钥/默认模型。</p>
	 *
	 * @param args 可选：properties 文件路径（默认 sure-ai-proxy.properties）
	 * @throws Exception 启动失败
	 */
	public static void main(String[] args) throws Exception {
		String file = args.length > 0 ? args[0] : "sure-ai-proxy.properties";
		Path path = Path.of(file);
		ProxyConfig config = Files.exists(path)
				? ProxyConfig.load(path)
				: ProxyConfig.defaults();
		ClientRegistry registry = new ClientRegistry();
		GatewayClient gateway = new GatewayClient(registry);
		SureAiProxy proxy = new SureAiProxy(gateway, registry, config);
		proxy.start();
		System.out.println("sureai-proxy listening on http://localhost:" + proxy.boundPort() + "/v1");
	}
}
