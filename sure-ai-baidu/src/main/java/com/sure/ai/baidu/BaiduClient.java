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

package com.sure.ai.baidu;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;

/**
 * 百度智能云千帆（文心 ERNIE）客户端。
 *
 * <p>自研实现，不复用 OpenAI 兼容引擎。差异点：</p>
 * <ul>
 *   <li>两步鉴权：先用 API Key + Secret Key 调 OAuth 换 access_token，再把 token 拼在请求 URL 查询串上；
 *       token 按 {@code expires_in} 缓存（提前 60 秒刷新），并发下只刷新一次。</li>
 *   <li>对话模型是路径参数：{@code /rpc/2.0/ai_custom/v1/wenxinworkshop/chat/{model}}，请求体无 model 字段。</li>
 *   <li>非流式响应正文在 {@code result} 字段，流式 SSE 每个分片的增量在 {@code result}，结束标志 {@code is_end=true}。</li>
 *   <li>错误体为 {@code {error_code, error_msg}}，映射到 {@link AiApiException#getErrorCode()}。</li>
 * </ul>
 *
 * <p>默认 baseUrl：{@code https://aip.baidubce.com}。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class BaiduClient extends AbstractAiClient implements AiClient, EmbeddingClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://aip.baidubce.com";

	/** access_token 提前刷新的安全余量（毫秒）。 */
	private static final long TOKEN_LEEWAY_MS = 60_000L;

	/** secretKey 在 extraHeaders 中的键名。 */
	public static final String SECRET_KEY_HEADER = "secretKey";

	/** secretKey。 */
	private final String secretKey;

	/** 已缓存的 access_token。 */
	private volatile String cachedToken;

	/** token 过期时间戳（毫秒）。 */
	private volatile long tokenExpireAt;

	/** token 刷新锁。 */
	private final Object tokenLock = new Object();

	/**
	 * 构造客户端。
	 *
	 * @param config 配置：apiKey 为千帆 API Key，secretKey 需放在 extraHeaders("secretKey", ...)
	 */
	public BaiduClient(AiConfig config) {
		super(withDefaults(config));
		this.secretKey = config.extraHeaders().get(SECRET_KEY_HEADER);
	}

	@Override
	public String name() {
		return "baidu";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		// 百度鉴权走 URL 查询串 access_token，不走 Authorization 头
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String token = getAccessToken();
		String path = "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/" + request.model()
			+ "?access_token=" + token;
		JsonObject body = buildChatBody(request, false);
		try {
			PostResult result = doPostRaw(path, body);
			return parseChatResponse(result.json(), result.rawBody(), request.model());
		} catch (AiApiException ex) {
			throw (AiApiException) enrichError(ex);
		}
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		String token = getAccessToken();
		String path = "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/" + request.model()
			+ "?access_token=" + token;
		JsonObject body = buildChatBody(request, true);
		try {
			doPostStream(path, body, el -> consumer.accept(parseStreamChunk(el)));
		} catch (AiApiException ex) {
			throw (AiApiException) enrichError(ex);
		}
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		String token = getAccessToken();
		String path = "/rpc/2.0/ai_custom/v1/wenxinworkshop/embeddings/" + request.model()
			+ "?access_token=" + token;
		JsonObject body = Json.object();
		JsonArray input = Json.array();
		for (String s : request.input()) {
			input.add(s);
		}
		body.put("input", input);
		try {
			JsonObject resp = doPost(path, body);
			return parseEmbeddingResponse(resp);
		} catch (AiApiException ex) {
			throw (AiApiException) enrichError(ex);
		}
	}

	/** 解析非流式对话响应：result 字段为正文。 */
	private ChatResponse parseChatResponse(JsonObject resp, String raw, String model) {
		String id = resp.optString("id", null);
		String result = resp.optString("result", null);
		ChatMessage message = ChatMessage.of(Role.ASSISTANT, result, null, null, null, null);
		List<Choice> choices = List.of(Choice.of(0, message, "stop"));
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
		}
		return ChatResponse.of(id, model, choices, usage, raw);
	}

	/** 解析流式分片：result 为增量，is_end=true 时结束。 */
	private ChatStreamChunk parseStreamChunk(JsonElement el) {
		JsonObject o = el.getAsJsonObject();
		String delta = o.optString("result", null);
		boolean isEnd = o.has("is_end") && o.getBoolean("is_end");
		String finish = isEnd ? "stop" : null;
		return ChatStreamChunk.of(o.optString("id", null), null, delta, null, finish);
	}

	/** 解析向量响应。 */
	private EmbeddingResponse parseEmbeddingResponse(JsonObject resp) {
		String model = resp.optString("model", null);
		JsonArray data = resp.getJsonArray("data");
		List<float[]> embeddings = new ArrayList<>();
		for (int i = 0; i < data.size(); i++) {
			JsonObject d = data.getJsonObject(i);
			JsonArray emb = d.getJsonArray("embedding");
			float[] vec = new float[emb.size()];
			for (int j = 0; j < emb.size(); j++) {
				vec[j] = (float) emb.getDouble(j);
			}
			embeddings.add(vec);
		}
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
		}
		return EmbeddingResponse.of(model, embeddings, usage);
	}

	/** 构造对话请求体（无 model 字段，model 在 URL 路径）。 */
	private JsonObject buildChatBody(ChatRequest req, boolean stream) {
		JsonObject body = Json.object();
		JsonArray messages = Json.array();
		for (ChatMessage m : req.messages()) {
			JsonObject o = Json.object();
			o.put("role", m.role().value());
			o.put("content", m.content() == null ? "" : m.content());
			messages.add(o);
		}
		body.put("messages", messages);
		body.put("stream", stream);
		if (req.temperature() != null) {
			body.put("temperature", req.temperature());
		}
		if (req.topP() != null) {
			body.put("top_p", req.topP());
		}
		if (req.maxTokens() != null) {
			body.put("max_output_tokens", req.maxTokens());
		}
		return body;
	}

	/** 获取有效 access_token：缓存未过期复用，否则双检锁重新换取。 */
	private String getAccessToken() {
		String token = this.cachedToken;
		if (token != null && System.currentTimeMillis() < this.tokenExpireAt - TOKEN_LEEWAY_MS) {
			return token;
		}
		synchronized (this.tokenLock) {
			if (this.cachedToken == null
				|| System.currentTimeMillis() >= this.tokenExpireAt - TOKEN_LEEWAY_MS) {
				fetchToken();
			}
			return this.cachedToken;
		}
	}

	/** 调 OAuth 接口换取 access_token 并更新缓存。 */
	private void fetchToken() {
		if (this.secretKey == null || this.secretKey.isBlank()) {
			throw new AiException("baidu secretKey is not configured (extraHeaders secretKey)");
		}
		String url = this.config.baseUrl() + "/oauth/2.0/token?grant_type=client_credentials"
			+ "&client_id=" + this.config.apiKey() + "&client_secret=" + this.secretKey;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.POST(java.net.http.HttpRequest.BodyPublishers.noBody())
			.build();
		HttpResponse<String> resp;
		try {
			resp = this.httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiTimeoutException("fetch baidu token failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("fetch baidu token interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw enrichError(new AiAuthException("baidu token http " + status, resp.body()));
		}
		JsonObject o = parseJson(resp.body());
		if (!o.has("access_token")) {
			throw new AiAuthException("baidu token response missing access_token", resp.body());
		}
		this.cachedToken = o.getString("access_token");
		long expiresInSec = o.get("expires_in").getAsLong();
		this.tokenExpireAt = System.currentTimeMillis() + expiresInSec * 1000L;
	}

	/** 从错误体解析 error_code / error_msg，保留异常类型。 */
	private AiApiException enrichError(AiApiException ex) {
		String raw = ex.getRawBody();
		if (raw == null || raw.isBlank()) {
			return ex;
		}
		try {
			JsonObject o = parseJson(raw);
			if (o.has("error_code")) {
				String code = String.valueOf(o.getInt("error_code"));
				String msg = o.optString("error_msg", ex.getMessage());
				if (ex instanceof AiAuthException) {
					return new AiAuthException(ex.getHttpStatus(), msg, raw);
				}
				return new AiApiException(ex.getHttpStatus(), code, msg, raw);
			}
		} catch (RuntimeException ignored) {
			// 错误体不是 JSON，原样抛出
		}
		return ex;
	}

	/** baseUrl 为空时补默认地址，其余配置原样保留。 */
	private static AiConfig withDefaults(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(config.apiKey())
			.baseUrl(DEFAULT_BASE_URL)
			.timeout(config.timeout())
			.connectTimeout(config.connectTimeout())
			.maxRetries(config.maxRetries());
		if (config.proxy() != null && !config.proxy().isBlank()) {
			b.proxy(config.proxy());
		}
		for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
			b.extraHeader(e.getKey(), e.getValue());
		}
		return b.build();
	}
}
