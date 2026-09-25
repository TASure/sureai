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

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
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
 * <p>运行期零第三方依赖：HTTP 委托给 {@link AbstractAiClient} 基类（JDK {@link java.net.http.HttpClient}），
 * 自动继承重试/限流/熔断/指标/缓存/代理/超时能力；鉴权基于自研 {@link AwsSigV4Signer}
 * （AWS Signature V4），通过基类通用 {@link #signRequest} 钩子挂载——SigV4 需要请求体原文
 * 计算 payload 摘要，故走签名钩子而非 {@link #applyAuth}。</p>
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
public final class BedrockClient extends AbstractAiClient implements AiClient {

	/** Bedrock runtime 服务名（SigV4 service）。 */
	public static final String SERVICE = "bedrock";

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
	 * <p>内部构建 {@link AiConfig}：{@code apiKey=accessKey}、{@code baseUrl=endpoint}，
	 * 其余跨切面能力（重试/熔断/指标/限流/代理/超时）取 {@link AiConfig} 默认值。</p>
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
		this(buildConfig(accessKey, endpointOverride, region), secretKey, sessionToken, region, modelId);
	}

	/**
	 * 全参构造（供测试注入 {@link AiConfig} 的 circuitBreaker/metricsCollector/maxRetries/rateLimitQps）。
	 *
	 * <p>{@code config.apiKey()} 即 AWS Access Key，{@code config.baseUrl()} 即 Bedrock runtime 端点；
	 * secretKey/sessionToken/region 作为 Bedrock 特有凭证用于 SigV4 签名，不走 AiConfig。</p>
	 *
	 * @param config        完整配置（含跨切面能力）
	 * @param secretKey     AWS Secret Access Key
	 * @param sessionToken  临时会话令牌（可空）
	 * @param region        AWS 区域
	 * @param modelId       默认模型 ID（可空）
	 */
	BedrockClient(AiConfig config, String secretKey, String sessionToken, String region, String modelId) {
		super(config);
		if (region == null || region.isBlank()) {
			throw new AiException("AWS region must not be blank");
		}
		this.region = region;
		this.defaultModelId = modelId;
		this.endpoint = stripTrailingSlash(config.baseUrl());
		URI uri = URI.create(this.endpoint);
		this.host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ':' + uri.getPort();
		this.signer = new AwsSigV4Signer(config.apiKey(), secretKey, sessionToken, region, SERVICE);
	}

	/** 由凭证与端点构建基类 AiConfig（apiKey=accessKey，baseUrl=endpoint）。 */
	private static AiConfig buildConfig(String accessKey, String endpointOverride, String region) {
		String endpoint = (endpointOverride != null && !endpointOverride.isBlank())
			? stripTrailingSlash(endpointOverride)
			: "https://bedrock-runtime." + region + ".amazonaws.com";
		return AiConfig.builder().apiKey(accessKey).baseUrl(endpoint).build();
	}

	@Override
	public String name() {
		return "bedrock";
	}

	/**
	 * SigV4 不通过简单 Authorization 头完成，而是经 {@link #signRequest} 钩子（需请求体原文），
	 * 故此空实现。
	 */
	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		// 签名见 signRequest
	}

	/**
	 * 覆写基类签名钩子：从完整 URL 提取 host/path/query，调用 {@link AwsSigV4Signer} 计算全部
	 * 签名头（Authorization/X-Amz-Date/X-Amz-Content-Sha256/可选 X-Amz-Security-Token）。
	 *
	 * <p>每次重试都会重新签名（时间戳刷新），这是 SigV4 的期望行为。</p>
	 */
	@Override
	protected Map<String, String> signRequest(String method, String url, String body) {
		URI uri = URI.create(url);
		String path = uri.getRawPath();
		String hostHeader = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ':' + uri.getPort();
		String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
		return this.signer.sign(method, hostHeader, path, query, body, ZonedDateTime.now());
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String model = resolveModel(request.model());
		JsonObject body = buildConverseBody(request);
		String path = "/model/" + model + "/converse";
		PostResult result = doPostRaw(path, body);
		ChatResponse resp = parseConverseResponse(result.rawBody(), model);
		TokenUsage usage = resp.usage();
		if (usage != null) {
			notifyTokenUsage(model, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
		}
		return resp;
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		String model = resolveModel(request.model());
		JsonObject body = buildConverseBody(request);
		String path = "/model/" + model + "/converse-stream";
		doPostStream(path, body, el -> handleStreamChunk(el, consumer));
	}

	/**
	 * Bedrock Titan Embeddings 暂未接入；如后续实现，应实现
	 * {@link com.sure.ai.client.EmbeddingClient} 接口。
	 */
	public void embedNotSupported() {
		throw new AiException("Bedrock embeddings (Titan) is not supported yet");
	}

	/** 构造 Converse 请求体（system/messages/inferenceConfig）。 */
	private JsonObject buildConverseBody(ChatRequest request) {
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
		return root;
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

	/**
	 * 处理单个 SSE 分片（基类 doPostStream 已把每个 data 行解析为 JsonElement）。
	 *
	 * <p>Bedrock 在 data JSON 内嵌 {@code eventType} 字段，据此分发：messageStart 投角色、
	 * contentBlockDelta 投文本增量、messageStop 投结束原因；metadata 含用量但分片模型无用量
	 * 字段，此处仅消费不投递。</p>
	 */
	private void handleStreamChunk(JsonElement el, Consumer<ChatStreamChunk> consumer) {
		if (el == null || !el.isObject()) {
			return;
		}
		JsonObject evt = el.getAsJsonObject();
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
