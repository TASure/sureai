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

package com.sure.ai.ollama;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
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
 * Ollama 本地推理服务客户端。
 *
 * <p>非 OpenAI 兼容协议：无鉴权；流式响应为 NDJSON（逐行 JSON，非 SSE）；
 * 使用 {@code /api/chat} 对话与 {@code /api/embed} 向量端点。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class OllamaClient extends AbstractAiClient implements AiClient, EmbeddingClient {

	/** 对话接口路径。 */
	private static final String CHAT_PATH = "/api/chat";

	/** 向量接口路径。 */
	private static final String EMBED_PATH = "/api/embed";

	/** 默认 baseUrl。 */
	private static final String DEFAULT_BASE_URL = "http://localhost:11434";

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（baseUrl 为空时使用默认本地地址）
	 */
	public OllamaClient(AiConfig config) {
		super(config);
	}

	@Override
	public String name() {
		return "ollama";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		// Ollama 本地服务无鉴权
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		JsonObject body = buildChatBody(request, false);
		PostResult result = doPostRaw(CHAT_PATH, body);
		return parseChatResponse(result.json(), result.rawBody());
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		JsonObject body = buildChatBody(request, true);
		streamChat(body, consumer);
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		JsonObject body = Json.object();
		body.put("model", request.model());
		JsonArray input = Json.array();
		for (String s : request.input()) {
			input.add(s);
		}
		body.put("input", input);
		JsonObject resp = doPost(EMBED_PATH, body);
		return parseEmbedResponse(resp);
	}

	// ---------------------------------------------------------------------
	// 请求序列化
	// ---------------------------------------------------------------------

	/** 构造 /api/chat 请求体。 */
	private JsonObject buildChatBody(ChatRequest req, boolean stream) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		JsonArray messages = Json.array();
		for (ChatMessage m : req.messages()) {
			JsonObject o = Json.object();
			o.put("role", m.role().value());
			if (m.content() != null) {
				o.put("content", m.content());
			}
			messages.add(o);
		}
		body.put("messages", messages);
		body.put("stream", stream);
		JsonObject options = Json.object();
		if (req.temperature() != null) {
			options.put("temperature", req.temperature());
		}
		if (req.topP() != null) {
			options.put("top_p", req.topP());
		}
		if (req.maxTokens() != null) {
			options.put("num_predict", req.maxTokens());
		}
		if (req.stop() != null) {
			options.set("stop", Json.toElement(req.stop()));
		}
		if (options.size() > 0) {
			body.put("options", options);
		}
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	// ---------------------------------------------------------------------
	// 非流式响应解析
	// ---------------------------------------------------------------------

	/** 解析 /api/chat 非流式响应。 */
	private ChatResponse parseChatResponse(JsonObject resp, String rawJson) {
		String model = resp.optString("model", null);
		String content = null;
		if (resp.has("message")) {
			JsonObject msg = resp.getJsonObject("message");
			if (msg.has("content") && !msg.get("content").isNull()) {
				content = msg.getString("content");
			}
		}
		String doneReason = resp.optString("done_reason", null);
		ChatMessage message = ChatMessage.of(Role.ASSISTANT, content, null, null, null, null);
		List<Choice> choices = List.of(Choice.of(0, message, doneReason));
		TokenUsage usage = null;
		int promptTokens = resp.optInt("prompt_eval_count", 0);
		int completionTokens = resp.optInt("eval_count", 0);
		if (promptTokens > 0 || completionTokens > 0) {
			usage = TokenUsage.of(promptTokens, completionTokens, promptTokens + completionTokens);
		}
		return ChatResponse.of(null, model, choices, usage, rawJson);
	}

	/** 解析 /api/embed 响应。 */
	private EmbeddingResponse parseEmbedResponse(JsonObject resp) {
		String model = resp.optString("model", null);
		List<float[]> embeddings = new ArrayList<>();
		if (resp.has("embeddings")) {
			JsonArray embArr = resp.getJsonArray("embeddings");
			for (int i = 0; i < embArr.size(); i++) {
				JsonArray vec = embArr.getJsonArray(i);
				float[] fv = new float[vec.size()];
				for (int j = 0; j < vec.size(); j++) {
					fv[j] = (float) vec.getDouble(j);
				}
				embeddings.add(fv);
			}
		}
		return EmbeddingResponse.of(model, embeddings, null);
	}

	// ---------------------------------------------------------------------
	// NDJSON 流式解析（非 SSE）
	// ---------------------------------------------------------------------

	/**
	 * 自行实现 NDJSON 流式 POST：逐行读取 InputStream，每行解析为 JSON 对象。
	 *
	 * @param body     请求体
	 * @param consumer 分片消费者
	 */
	private void streamChat(JsonObject body, Consumer<ChatStreamChunk> consumer) {
		String url = resolveUrl(CHAT_PATH);
		String payload = Json.stringify(body);
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.header("Accept", "application/x-ndjson")
			.POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
			.build();
		HttpResponse<InputStream> resp;
		try {
			resp = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException ex) {
			throw new AiException("stream request failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("stream interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			String rawBody;
			try (InputStream in = resp.body()) {
				rawBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException ex) {
				rawBody = "";
			}
			throw mapError(status, rawBody);
		}
		try (InputStream in = resp.body();
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonObject obj = Json.parse(line).getAsJsonObject();
				handleNdjsonLine(obj, consumer);
			}
		} catch (IOException ex) {
			throw new AiException("NDJSON read failed: " + ex.getMessage(), ex);
		}
	}

	/** 拼接完整 URL（resolveUrl 是 private，此处自行拼接）。 */
	private String resolveUrl(String path) {
		String base = this.config.baseUrl();
		if (base == null || base.isBlank()) {
			throw new AiException("baseUrl is not configured");
		}
		if (base.endsWith("/") && path.startsWith("/")) {
			return base + path.substring(1);
		}
		if (!base.endsWith("/") && !path.startsWith("/")) {
			return base + "/" + path;
		}
		return base + path;
	}

	/** 处理单行 NDJSON。 */
	private void handleNdjsonLine(JsonObject obj, Consumer<ChatStreamChunk> consumer) {
		boolean done = obj.has("done") && obj.getBoolean("done");
		if (!done && obj.has("message")) {
			JsonObject msg = obj.getJsonObject("message");
			if (msg.has("content") && !msg.get("content").isNull()) {
				String text = msg.getString("content");
				if (!text.isEmpty()) {
					consumer.accept(ChatStreamChunk.of(null, null, text, null, null));
				}
			}
		}
		if (done) {
			String doneReason = obj.optString("done_reason", null);
			consumer.accept(ChatStreamChunk.of(null, null, null, null, doneReason));
		}
	}
}
