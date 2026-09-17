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

package com.sure.ai.gemini;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ImageClient;
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
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.ImageResult;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Role;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * Google Gemini API 客户端。
 *
 * <p>非 OpenAI 兼容协议：API Key 作为 {@code ?key=} 查询参数鉴权；请求体使用
 * {@code contents/parts} 结构；system 消息映射为顶级 {@code systemInstruction} 字段；
 * 流式使用 {@code streamGenerateContent?alt=sse}。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class GeminiClient extends AbstractAiClient implements AiClient, EmbeddingClient, ImageClient {

	/** 默认 baseUrl。 */
	private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（baseUrl 为空时使用默认地址）
	 */
	public GeminiClient(AiConfig config) {
		super(config);
	}

	@Override
	public String name() {
		return "gemini";
	}

	@Override
	protected void applyAuth(java.net.http.HttpRequest.Builder requestBuilder, AiConfig cfg) {
		// API Key 通过查询参数传递，此处无需设置头
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String path = buildPath(request.model(), "generateContent");
		JsonObject body = buildChatBody(request);
		PostResult result = doPostRaw(path, body);
		return parseChatResponse(result.json(), result.rawBody());
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		String path = buildStreamPath(request.model());
		JsonObject body = buildChatBody(request);
		doPostStream(path, body, el -> consumer.accept(parseStreamChunk(el.getAsJsonObject())));
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		List<float[]> vectors = new ArrayList<>();
		for (String text : request.input()) {
			vectors.add(embedSingle(request.model(), text));
		}
		return EmbeddingResponse.of(request.model(), vectors, null);
	}

	@Override
	public ImageResponse generate(ImageRequest request) {
		String path = buildPath(request.model(), "generateContent");
		JsonObject body = Json.object();
		JsonArray contents = Json.array();
		JsonObject content = Json.object();
		content.put("role", "user");
		JsonArray parts = Json.array();
		JsonObject textPart = Json.object();
		textPart.put("text", request.prompt());
		parts.add(textPart);
		content.put("parts", parts);
		contents.add(content);
		body.put("contents", contents);
		JsonObject genConfig = Json.object();
		JsonArray modalities = Json.array();
		modalities.add("IMAGE");
		modalities.add("TEXT");
		genConfig.put("responseModalities", modalities);
		JsonObject imageConfig = Json.object();
		if (request.n() != null) {
			imageConfig.put("numberOfImages", request.n());
		}
		if (request.size() != null && !request.size().isBlank()) {
			imageConfig.put("imageSize", request.size());
		}
		if (imageConfig.size() > 0) {
			genConfig.put("imageConfig", imageConfig);
		}
		body.put("generationConfig", genConfig);
		PostResult result = doPostRaw(path, body);
		return parseImageResponse(result.json(), result.rawBody());
	}

	/** 解析图像生成响应：从 candidates[0].content.parts 提取 inlineData。 */
	private ImageResponse parseImageResponse(JsonObject resp, String rawJson) {
		List<ImageResult> results = new ArrayList<>();
		JsonArray candidates = resp.has("candidates") ? resp.getJsonArray("candidates") : null;
		if (candidates != null && !candidates.isEmpty()) {
			JsonObject cand = candidates.getJsonObject(0);
			JsonObject content = cand.has("content") ? cand.getJsonObject("content") : null;
			if (content != null && content.has("parts")) {
				JsonArray parts = content.getJsonArray("parts");
				for (int i = 0; i < parts.size(); i++) {
					JsonObject p = parts.getJsonObject(i);
					JsonObject inline = p.has("inlineData") ? p.getJsonObject("inlineData")
						: (p.has("inline_data") ? p.getJsonObject("inline_data") : null);
					if (inline != null) {
						String data = inline.optString("data", null);
						if (data != null && !data.isEmpty()) {
							results.add(ImageResult.ofB64(data));
						}
					}
				}
			}
		}
		return ImageResponse.of(0, results, rawJson);
	}

	/** 单条文本向量。 */
	private float[] embedSingle(String model, String text) {
		String path = buildPath(model, "embedContent");
		JsonObject body = Json.object();
		body.put("model", "models/" + model);
		JsonObject content = Json.object();
		JsonArray parts = Json.array();
		JsonObject textPart = Json.object();
		textPart.put("text", text);
		parts.add(textPart);
		content.put("parts", parts);
		body.put("content", content);
		JsonObject resp = doPost(path, body);
		JsonObject embedding = resp.getJsonObject("embedding");
		JsonArray values = embedding.getJsonArray("values");
		float[] vec = new float[values.size()];
		for (int i = 0; i < values.size(); i++) {
			vec[i] = (float) values.getDouble(i);
		}
		return vec;
	}

	// ---------------------------------------------------------------------
	// URL 构建
	// ---------------------------------------------------------------------

	/** 拼接非流式路径：models/{model}:{action}?key=xxx。 */
	private String buildPath(String model, String action) {
		return "models/" + model + ":" + action + "?key=" + this.config.apiKey();
	}

	/** 拼接流式路径：models/{model}:streamGenerateContent?alt=sse&key=xxx。 */
	private String buildStreamPath(String model) {
		return "models/" + model + ":streamGenerateContent?alt=sse&key=" + this.config.apiKey();
	}

	// ---------------------------------------------------------------------
	// 请求序列化
	// ---------------------------------------------------------------------

	/** 构造 Gemini 请求体。 */
	private JsonObject buildChatBody(ChatRequest req) {
		JsonObject body = Json.object();
		JsonArray contents = Json.array();
		StringBuilder systemText = new StringBuilder();
		for (ChatMessage m : req.messages()) {
			if (m.role() == Role.SYSTEM) {
				if (m.content() != null) {
					if (systemText.length() > 0) {
						systemText.append('\n');
					}
					systemText.append(m.content());
				}
				continue;
			}
			contents.add(serializeContent(m));
		}
		body.put("contents", contents);
		if (systemText.length() > 0) {
			JsonObject sysInst = Json.object();
			JsonArray sysParts = Json.array();
			JsonObject p = Json.object();
			p.put("text", systemText.toString());
			sysParts.add(p);
			sysInst.put("parts", sysParts);
			body.put("systemInstruction", sysInst);
		}
		JsonObject genConfig = Json.object();
		if (req.temperature() != null) {
			genConfig.put("temperature", req.temperature());
		}
		if (req.topP() != null) {
			genConfig.put("topP", req.topP());
		}
		if (req.maxTokens() != null) {
			genConfig.put("maxOutputTokens", req.maxTokens());
		}
		if (req.stop() != null) {
			genConfig.set("stopSequences", Json.toElement(req.stop()));
		}
		if (genConfig.size() > 0) {
			body.put("generationConfig", genConfig);
		}
		if (req.tools() != null && !req.tools().isEmpty()) {
			JsonArray tools = Json.array();
			JsonObject tool = Json.object();
			JsonArray decls = Json.array();
			for (ToolSpec spec : req.tools()) {
				decls.add(serializeFunctionDecl(spec.function()));
			}
			tool.put("function_declarations", decls);
			tools.add(tool);
			body.put("tools", tools);
		}
		return body;
	}

	/** 序列化单条消息为 Gemini Content 对象。 */
	private JsonObject serializeContent(ChatMessage m) {
		JsonObject c = Json.object();
		String roleName = m.role() == Role.ASSISTANT ? "model" : "user";
		c.put("role", roleName);
		JsonArray parts = Json.array();
		if (m.parts() != null) {
			for (MessagePart part : m.parts()) {
				parts.add(serializePart(part));
			}
		} else if (m.content() != null) {
			JsonObject p = Json.object();
			p.put("text", m.content());
			parts.add(p);
		}
		c.put("parts", parts);
		return c;
	}

	/** 序列化多模态片段。 */
	private JsonObject serializePart(MessagePart p) {
		JsonObject o = Json.object();
		if (p instanceof TextPart tp) {
			o.put("text", tp.text());
		} else if (p instanceof ImagePart ip) {
			JsonObject inline = Json.object();
			inline.put("mime_type", ip.mimeType() != null ? ip.mimeType() : "image/png");
			inline.put("data", ip.base64() != null ? ip.base64() : "");
			o.put("inline_data", inline);
		}
		return o;
	}

	/** 序列化函数声明。 */
	private JsonObject serializeFunctionDecl(ToolFunction fn) {
		JsonObject d = Json.object();
		d.put("name", fn.name());
		if (fn.description() != null) {
			d.put("description", fn.description());
		}
		if (fn.parameters() != null) {
			d.set("parameters", Json.parse(fn.parameters()));
		}
		return d;
	}

	// ---------------------------------------------------------------------
	// 响应解析
	// ---------------------------------------------------------------------

	/** 解析非流式响应。 */
	private ChatResponse parseChatResponse(JsonObject resp, String rawJson) {
		String model = resp.optString("modelVersion", null);
		List<Choice> choices = new ArrayList<>();
		JsonArray candidates = resp.has("candidates") ? resp.getJsonArray("candidates") : null;
		if (candidates != null && !candidates.isEmpty()) {
			JsonObject cand = candidates.getJsonObject(0);
			String finishReason = cand.optString("finishReason", null);
			JsonObject content = cand.has("content") ? cand.getJsonObject("content") : null;
			ChatMessage message = parseContent(content);
			choices.add(Choice.of(0, message, finishReason));
		}
		TokenUsage usage = null;
		if (resp.has("usageMetadata")) {
			JsonObject u = resp.getJsonObject("usageMetadata");
			int prompt = u.optInt("promptTokenCount", 0);
			int completion = u.optInt("candidatesTokenCount", 0);
			int total = u.optInt("totalTokenCount", prompt + completion);
			usage = TokenUsage.of(prompt, completion, total);
		}
		return ChatResponse.of(null, model, choices, usage, rawJson);
	}

	/** 解析 Content 对象为 ChatMessage。 */
	private ChatMessage parseContent(JsonObject content) {
		if (content == null) {
			return ChatMessage.of(Role.ASSISTANT, "", null, null, null, null);
		}
		StringBuilder text = new StringBuilder();
		List<ToolCall> calls = null;
		JsonArray parts = content.has("parts") ? content.getJsonArray("parts") : null;
		if (parts != null) {
			for (int i = 0; i < parts.size(); i++) {
				JsonObject p = parts.getJsonObject(i);
				if (p.has("text") && !p.get("text").isNull()) {
					text.append(p.getString("text"));
				}
				if (p.has("functionCall")) {
					if (calls == null) {
						calls = new ArrayList<>();
					}
					JsonObject fc = p.getJsonObject("functionCall");
					String args = fc.has("args") ? fc.get("args").toString() : "{}";
					calls.add(ToolCall.of(null, fc.optString("name", null), args));
				}
			}
		}
		return ChatMessage.of(Role.ASSISTANT, text.length() > 0 ? text.toString() : null,
			null, null, null, calls);
	}

	/** 解析流式分片。 */
	private ChatStreamChunk parseStreamChunk(JsonObject chunk) {
		JsonArray candidates = chunk.has("candidates") ? chunk.getJsonArray("candidates") : null;
		if (candidates == null || candidates.isEmpty()) {
			return ChatStreamChunk.of(null, null, null, null, null);
		}
		JsonObject cand = candidates.getJsonObject(0);
		String finishReason = cand.optString("finishReason", null);
		String text = null;
		JsonObject content = cand.has("content") ? cand.getJsonObject("content") : null;
		if (content != null && content.has("parts")) {
			JsonArray parts = content.getJsonArray("parts");
			if (parts != null && !parts.isEmpty()) {
				JsonObject p = parts.getJsonObject(0);
				if (p.has("text") && !p.get("text").isNull()) {
					text = p.getString("text");
				}
			}
		}
		return ChatStreamChunk.of(null, null, text, null, finishReason);
	}
}
