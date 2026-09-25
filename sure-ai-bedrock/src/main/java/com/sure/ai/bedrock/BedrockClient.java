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

package com.sure.ai.bedrock;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.http.SseEvent;
import com.sure.ai.internal.http.SseLineReader;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.internal.json.JsonParser;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;

/**
 * AWS Bedrock 平台客户端（统一 Converse API）。
 *
 * <p>运行期零第三方依赖：HTTP 基于 JDK {@link HttpClient}，鉴权基于自研
 * {@link AwsSigV4Signer}（AWS Signature V4），JSON 复用 sure-ai-core 自研实现，
 * SSE 复用 core 的 {@link SseLineReader}。</p>
 *
 * <p>非流式对话调用 {@code POST /model/{modelId}/converse}，流式调用
 * {@code POST /model/{modelId}/converse-stream}。请求体映射：</p>
 * <ul>
 *   <li>{@code system[]}：来自 {@link Role#SYSTEM} 消息，每项 {@code {"text": "..."}}；</li>
 *   <li>{@code messages[]}：非 system 消息，每项 {@code {"role","content":[{"text":...}]}}；</li>
 *   <li>{@code inferenceConfig}：{@code maxTokens/temperature/topP}。</li>
 * </ul>
 *
 * <p>响应映射：{@code output.message.content[].text} 聚合为回复文本，
 * {@code stopReason} 映射为 {@link Choice#finishReason()}，
 * {@code usage.inputTokens/outputTokens} 映射为 {@link TokenUsage}。</p>
 *
 * <p>Embeddings（Titan）暂未实现，调用 {@link #embedNotSupported()} 抛 {@link AiException}。</p>
 *
 * <p>官方文档：<a href="https://docs.aws.amazon.com/bedrock/">https://docs.aws.amazon.com/bedrock/</a></p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class BedrockClient implements AiClient {

	/** Bedrock runtime 服务名（SigV4 service）。 */
	public static final String SERVICE = "bedrock";

	/** 默认连接超时（秒）。 */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** 默认请求超时（秒）。 */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

	/** 区域。 */
	private final String region;

	/** 默认模型 ID（可空，chat 请求内指定 model）。 */
	private final String defaultModelId;

	/** 运行时端点（含协议，如 https://bedrock-runtime.us-east-1.amazonaws.com）。 */
	private final String endpoint;

	/** 参与签名的 Host（含非默认端口）。 */
	private final String host;

	/** SigV4 签名器。 */
	private final AwsSigV4Signer signer;

	/** JDK HTTP 客户端。 */
	private final HttpClient httpClient;

	/**
	 * 构造客户端，使用默认 Bedrock runtime 端点。
	 *
	 * @param accessKey    AWS Access Key ID（必填）
	 * @param secretKey    AWS Secret Access Key（必填）
	 * @param sessionToken 临时会话令牌（可选，可为 null）
	 * @param region       AWS 区域（必填，如 us-east-1）
	 * @param modelId      默认模型 ID（可选，可为 null）
	 */
	public BedrockClient(String accessKey, String secretKey, String sessionToken,
			String region, String modelId) {
		this(accessKey, secretKey, sessionToken, region, modelId, null);
	}

	/**
	 * 构造客户端（可覆盖端点，主要用于本地 mock 测试）。
	 *
	 * @param accessKey      AWS Access Key ID（必填）
	 * @param secretKey      AWS Secret Access Key（必填）
	 * @param sessionToken   临时会话令牌（可选，可为 null）
	 * @param region         AWS 区域（必填）
	 * @param modelId        默认模型 ID（可选）
	 * @param endpointOverride 端点覆盖（null 则用默认 bedrock-runtime 端点）
	 */
	BedrockClient(String accessKey, String secretKey, String sessionToken,
			String region, String modelId, String endpointOverride) {
		if (region == null || region.isBlank()) {
			throw new AiException("AWS region must not be blank");
		}
		this.region = region;
		this.defaultModelId = modelId;
		this.endpoint = (endpointOverride != null && !endpointOverride.isBlank())
			? stripTrailingSlash(endpointOverride)
			: "https://bedrock-runtime." + region + ".amazonaws.com";
		URI uri = URI.create(this.endpoint);
		this.host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ':' + uri.getPort();
		this.signer = new AwsSigV4Signer(accessKey, secretKey, sessionToken, region, SERVICE);
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.build();
	}

	@Override
	public String name() {
		return "bedrock";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String model = resolveModel(request.model());
		String body = buildConverseBody(request);
		String path = "/model/" + model + "/converse";
		String url = this.endpoint + path;
		Map<String, String> headers = signer.sign("POST", this.host, path, "", body, ZonedDateTime.now());
		HttpRequest httpRequest = newRequestBuilder(url, body, headers)
			.build();
		try {
			HttpResponse<String> resp = this.httpClient.send(httpRequest,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			ensureSuccess(resp.statusCode(), resp.body());
			return parseConverseResponse(resp.body(), model);
		} catch (java.io.IOException ex) {
			throw new AiException("Bedrock converse failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("Bedrock converse interrupted: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		String model = resolveModel(request.model());
		String body = buildConverseBody(request);
		String path = "/model/" + model + "/converse-stream";
		String url = this.endpoint + path;
		Map<String, String> headers = signer.sign("POST", this.host, path, "", body, ZonedDateTime.now());
		HttpRequest httpRequest = newRequestBuilder(url, body, headers)
			.build();
		try {
			HttpResponse<InputStream> resp = this.httpClient.send(httpRequest,
				HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() >= 300) {
				ensureSuccess(resp.statusCode(), readBodySafe(resp.body()));
			}
			SseLineReader.read(resp.body(), StandardCharsets.UTF_8,
				event -> handleStreamEvent(event, consumer));
		} catch (java.io.IOException ex) {
			throw new AiException("Bedrock converse-stream failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("Bedrock converse-stream interrupted: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Bedrock Titan Embeddings 暂未接入；如后续实现，应实现
	 * {@link com.sure.ai.client.EmbeddingClient} 接口。
	 */
	public void embedNotSupported() {
		throw new AiException("Bedrock embeddings (Titan) is not supported yet");
	}

	@Override
	public void close() {
		// JDK HttpClient 无显式 close；shutdown 可由其内部 executor 完成，
		// 此处为空实现以满足 AiClient 生命周期契约。
	}

	/** 构造签名后的 POST 请求 Builder。 */
	private HttpRequest.Builder newRequestBuilder(String url, String body, Map<String, String> headers) {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		for (Map.Entry<String, String> e : headers.entrySet()) {
			// JDK HttpClient 禁止显式设置 Host 头（由 URI 自动生成），签名已将其纳入 CanonicalHeaders。
			if ("Host".equalsIgnoreCase(e.getKey())) {
				continue;
			}
			b.header(e.getKey(), e.getValue());
		}
		return b;
	}

	/** 构造 Converse 请求体（system/messages/inferenceConfig）。 */
	private String buildConverseBody(ChatRequest request) {
		JsonObject root = new JsonObject();
		JsonArray messages = new JsonArray();
		JsonArray system = new JsonArray();
		for (ChatMessage msg : request.messages()) {
			if (msg.role() == Role.SYSTEM) {
				JsonObject item = new JsonObject();
				item.put("text", nullSafe(msg.content()));
				system.add(item);
				continue;
			}
			JsonObject item = new JsonObject();
			item.put("role", msg.role().value());
			JsonArray content = new JsonArray();
			JsonObject block = new JsonObject();
			block.put("text", nullSafe(msg.content()));
			content.add(block);
			item.set("content", content);
			messages.add(item);
		}
		if (!system.isEmpty()) {
			root.set("system", system);
		}
		root.set("messages", messages);

		JsonObject inference = new JsonObject();
		if (request.maxTokens() != null) {
			inference.put("maxTokens", request.maxTokens());
		}
		if (request.temperature() != null) {
			inference.put("temperature", request.temperature());
		}
		if (request.topP() != null) {
			inference.put("topP", request.topP());
		}
		if (inference.size() > 0) {
			root.set("inferenceConfig", inference);
		}
		return root.toString();
	}

	/** 解析非流式 Converse 响应。 */
	private ChatResponse parseConverseResponse(String body, String model) {
		JsonObject root = (JsonObject) JsonParser.parse(body);
		StringBuilder text = new StringBuilder();
		JsonElement outputEl = root.get("output");
		if (outputEl != null && outputEl.isObject()) {
			JsonElement msgEl = outputEl.getAsJsonObject().get("message");
			if (msgEl != null && msgEl.isObject()) {
				JsonElement contentEl = msgEl.getAsJsonObject().get("content");
				if (contentEl != null && contentEl.isArray()) {
					JsonArray content = contentEl.getAsJsonArray();
					for (int i = 0; i < content.size(); i++) {
						JsonObject block = content.getJsonObject(i);
						if (block.has("text")) {
							text.append(block.getString("text"));
						}
					}
				}
			}
		}
		String stopReason = root.optString("stopReason", null);
		TokenUsage usage = parseUsage(root.get("usage"));
		ChatMessage message = ChatMessage.assistant(text.toString());
		List<Choice> choices = List.of(Choice.of(0, message, stopReason));
		return ChatResponse.of(null, model, choices, usage, body);
	}

	/** 解析用量对象。 */
	private TokenUsage parseUsage(JsonElement usageEl) {
		if (usageEl == null || !usageEl.isObject()) {
			return new TokenUsage(0, 0, 0);
		}
		JsonObject usage = usageEl.getAsJsonObject();
		int input = usage.optInt("inputTokens", 0);
		int output = usage.optInt("outputTokens", 0);
		int total = usage.optInt("totalTokens", input + output);
		return new TokenUsage(input, output, total);
	}

	/** 处理单个 SSE 事件并投递分片。 */
	private void handleStreamEvent(SseEvent event, Consumer<ChatStreamChunk> consumer) {
		JsonObject evt = (JsonObject) JsonParser.parse(event.data());
		String type = evt.optString("eventType", "");
		switch (type) {
			case "messageStart": {
				JsonElement msg = evt.get("message");
				String role = (msg != null && msg.isObject())
					? msg.getAsJsonObject().optString("role", "assistant") : "assistant";
				consumer.accept(ChatStreamChunk.of(null, Role.fromValue(role), null, null, null));
				break;
			}
			case "contentBlockDelta": {
				JsonElement delta = evt.get("delta");
				if (delta != null && delta.isObject() && delta.getAsJsonObject().has("text")) {
					String text = delta.getAsJsonObject().getString("text");
					consumer.accept(ChatStreamChunk.of(null, Role.ASSISTANT, text, null, null));
				}
				break;
			}
			case "messageStop": {
				String stopReason = evt.optString("stopReason", null);
				consumer.accept(ChatStreamChunk.of(null, Role.ASSISTANT, null, null, stopReason));
				break;
			}
			case "metadata":
				// 用量已在 metadata 事件中返回，分片模型无用量字段，此处仅消费不投递。
				break;
			default:
				// contentBlockStart / contentBlockStop 等无需投递文本。
				break;
		}
	}

	/** 校验 HTTP 状态，错误映射为对应异常。 */
	private void ensureSuccess(int statusCode, String body) {
		if (statusCode >= 300) {
			if (statusCode == 401 || statusCode == 403) {
				throw new AiAuthException(statusCode, "Bedrock auth failed", body);
			}
			throw new AiException("Bedrock API error " + statusCode + ": " + truncate(body));
		}
	}

	/** 读取流为字符串（尽力而为，失败返回空串）。 */
	private static String readBodySafe(InputStream in) {
		try {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException ex) {
			return "";
		}
	}

	/** 解析最终使用的模型 ID。 */
	private String resolveModel(String requestModel) {
		if (requestModel != null && !requestModel.isBlank()) {
			return requestModel;
		}
		if (this.defaultModelId != null && !this.defaultModelId.isBlank()) {
			return this.defaultModelId;
		}
		throw new AiException("model must not be blank and no default modelId configured");
	}

	private static String nullSafe(String s) {
		return s == null ? "" : s;
	}

	private static String stripTrailingSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}

	private static String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 500 ? s.substring(0, 500) : s;
	}

	/** 暴露给测试的签名器（验证签名头）。 */
	AwsSigV4Signer signer() {
		return this.signer;
	}

	/** 暴露给测试的 host。 */
	String host() {
		return this.host;
	}

	/** 暴露给测试的端点。 */
	String endpoint() {
		return this.endpoint;
	}
}
