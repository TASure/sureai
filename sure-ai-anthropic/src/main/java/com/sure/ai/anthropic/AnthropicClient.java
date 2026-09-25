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

package com.sure.ai.anthropic;

import java.io.IOException;
import java.io.InputStream;
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
import com.sure.ai.client.ModelsClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.http.SseEvent;
import com.sure.ai.internal.http.SseLineReader;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Model;
import com.sure.ai.model.Role;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * Anthropic Claude Messages API 客户端。
 *
 * <p>非 OpenAI 兼容协议：system 消息为顶级字段，max_tokens 必填，流式 SSE 带命名事件
 * （message_start / content_block_start / content_block_delta / content_block_stop /
 * message_delta / message_stop）。</p>
 *
 * <p>P1 能力：</p>
 * <ul>
 *   <li>多模态：{@link ImagePart}/{@link DocumentPart} 序列化为
 *       {@code type=image/document} 且 {@code source.type=base64} 的内容块；</li>
 *   <li>结构化输出：<b>Anthropic 无原生 response_format 字段</b>，本客户端通过强制
 *       {@code tool_use} 模拟——自动追加名为 {@code structured_output} 的工具并锁定
 *       {@code tool_choice}；模型返回的结构化 JSON 位于该 tool_use 块的 {@code input}，
 *       由响应解析器映射为 {@link ToolCall#argumentsJson()}；</li>
 *   <li>Prompt 缓存：{@link TextPart#cacheControl()} 非空时输出
 *       {@code cache_control} 断点；{@code extra("cache_control", ...)} 作为顶级字段透传。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AnthropicClient extends AbstractAiClient implements AiClient, ModelsClient {

	/** Messages 接口路径。 */
	private static final String CHAT_PATH = "/messages";

	/** 模型列表接口路径。 */
	private static final String MODELS_PATH = "/models";

	/** 默认 anthropic-version 头值。 */
	private static final String DEFAULT_API_VERSION = "2023-06-01";

	/** 默认 max_tokens。 */
	private static final int DEFAULT_MAX_TOKENS = 1024;

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（baseUrl 为空时使用默认地址）
	 */
	public AnthropicClient(AiConfig config) {
		super(config);
	}

	@Override
	public String name() {
		return "anthropic";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("x-api-key", cfg.apiKey());
		requestBuilder.header("anthropic-version", DEFAULT_API_VERSION);
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
		streamMessages(body, consumer);
	}

	// ---------------------------------------------------------------------
	// 请求序列化
	// ---------------------------------------------------------------------

	/** 构造 Anthropic Messages 请求体。 */
	private JsonObject buildChatBody(ChatRequest req, boolean stream) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		StringBuilder systemText = new StringBuilder();
		JsonArray messages = Json.array();
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
			messages.add(serializeMessage(m));
		}
		body.put("messages", messages);
		if (systemText.length() > 0) {
			body.put("system", systemText.toString());
		}
		int maxTokens = req.maxTokens() != null ? req.maxTokens() : DEFAULT_MAX_TOKENS;
		body.put("max_tokens", maxTokens);
		if (req.temperature() != null) {
			body.put("temperature", req.temperature());
		}
		if (req.topP() != null) {
			body.put("top_p", req.topP());
		}
		if (req.stop() != null) {
			body.put("stop_sequences", Json.toElement(req.stop()));
		}
		body.put("stream", stream);
		if (req.thinkingConfig() != null) {
			body.set("thinking", Json.toElement(req.thinkingConfig()));
		}
		JsonArray tools = Json.array();
		if (req.tools() != null) {
			for (ToolSpec spec : req.tools()) {
				tools.add(serializeTool(spec.function()));
			}
		}
		boolean structured = req.responseFormat() != null;
		if (structured) {
			tools.add(buildStructuredOutputTool(req.responseFormat()));
		}
		if (!tools.isEmpty()) {
			body.put("tools", tools);
		}
		if (structured) {
			JsonObject toolChoice = Json.object();
			toolChoice.put("type", "tool");
			toolChoice.put("name", "structured_output");
			body.set("tool_choice", toolChoice);
		} else if (req.toolChoice() != null) {
			body.put("tool_choice", Json.toElement(req.toolChoice()));
		}
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/**
	 * 构造强制结构化输出工具：{@code name=structured_output}，{@code input_schema}
	 * 取自 responseFormat 中的 json_schema（OpenAI 风格包装则提取内层 schema）。
	 *
	 * @param responseFormat 响应格式
	 * @return 工具定义
	 */
	static JsonObject buildStructuredOutputTool(Object responseFormat) {
		JsonObject tool = Json.object();
		tool.put("name", "structured_output");
		tool.put("description", "Structured output requested via responseFormat");
		tool.set("input_schema", extractInputSchema(responseFormat));
		return tool;
	}

	/** 从 responseFormat 提取 JSON Schema 对象。 */
	private static JsonObject extractInputSchema(Object responseFormat) {
		JsonElement el = Json.toElement(responseFormat);
		if (el.isObject()) {
			JsonObject jo = el.getAsJsonObject();
			if (jo.has("json_schema")) {
				JsonElement js = jo.get("json_schema");
				if (js.isObject()) {
					JsonObject jso = js.getAsJsonObject();
					if (jso.has("schema") && jso.get("schema").isObject()) {
						return jso.get("schema").getAsJsonObject();
					}
					return jso;
				}
			}
			return jo;
		}
		JsonObject fallback = Json.object();
		fallback.put("type", "object");
		return fallback;
	}

	/** 序列化单条消息。 */
	static JsonObject serializeMessage(ChatMessage m) {
		JsonObject o = Json.object();
		o.put("role", m.role().value());
		if (m.parts() != null && !m.parts().isEmpty()) {
			JsonArray blocks = Json.array();
			for (MessagePart part : m.parts()) {
				blocks.add(serializeContentBlock(part));
			}
			o.put("content", blocks);
		} else if (m.content() != null) {
			o.put("content", m.content());
		}
		if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
			JsonArray blocks = Json.array();
			for (ToolCall c : m.toolCalls()) {
				JsonObject block = Json.object();
				block.put("type", "tool_use");
				block.put("id", c.id());
				block.put("name", c.name());
				block.set("input", Json.parse(c.argumentsJson() == null ? "{}" : c.argumentsJson()));
				blocks.add(block);
			}
			o.put("content", blocks);
		}
		if (m.toolCallId() != null) {
			JsonObject block = Json.object();
			block.put("type", "tool_result");
			block.put("tool_use_id", m.toolCallId());
			block.put("content", m.content() != null ? m.content() : "");
			JsonArray blocks = Json.array();
			blocks.add(block);
			o.put("content", blocks);
		}
		return o;
	}

	/**
	 * 序列化多模态内容块。
	 *
	 * <p>TextPart 带 {@code cache_control} 断点；ImagePart/DocumentPart 用
	 * {@code source.type=base64} 内联（裸 base64）。</p>
	 *
	 * @param part 消息片段
	 * @return 内容块
	 */
	static JsonObject serializeContentBlock(MessagePart part) {
		JsonObject b = Json.object();
		if (part instanceof TextPart tp) {
			b.put("type", "text");
			b.put("text", tp.text());
			if (tp.cacheControl() != null) {
				JsonObject cc = Json.object();
				cc.put("type", tp.cacheControl().type());
				b.set("cache_control", cc);
			}
		} else if (part instanceof ImagePart ip) {
			b.put("type", "image");
			JsonObject src = Json.object();
			src.put("type", "base64");
			src.put("media_type", ip.mimeType() != null ? ip.mimeType() : "image/png");
			src.put("data", ip.base64() != null ? ip.base64() : "");
			b.set("source", src);
		} else if (part instanceof DocumentPart dp) {
			b.put("type", "document");
			JsonObject src = Json.object();
			src.put("type", "base64");
			src.put("media_type", dp.mimeType() != null ? dp.mimeType() : "application/pdf");
			src.put("data", dp.data() != null ? dp.data() : "");
			b.set("source", src);
		}
		return b;
	}

	/** 序列化工具定义。 */
	private JsonObject serializeTool(ToolFunction fn) {
		JsonObject t = Json.object();
		t.put("name", fn.name());
		if (fn.description() != null) {
			t.put("description", fn.description());
		}
		if (fn.parameters() != null) {
			t.set("input_schema", Json.parse(fn.parameters()));
		}
		return t;
	}

	// ---------------------------------------------------------------------
	// 非流式响应解析
	// ---------------------------------------------------------------------

	/** 解析非流式响应。 */
	private ChatResponse parseChatResponse(JsonObject resp, String rawJson) {
		String id = resp.optString("id", null);
		String model = resp.optString("model", null);
		StringBuilder text = new StringBuilder();
		StringBuilder thinking = new StringBuilder();
		List<ToolCall> toolCalls = new ArrayList<>();
		if (resp.has("content")) {
			JsonArray content = resp.getJsonArray("content");
			for (int i = 0; i < content.size(); i++) {
				JsonObject block = content.getJsonObject(i);
				String type = block.optString("type", "");
				if ("text".equals(type) && block.has("text")) {
					text.append(block.getString("text"));
				} else if ("thinking".equals(type)) {
					if (block.has("thinking") && !block.get("thinking").isNull()) {
						thinking.append(block.getString("thinking"));
					} else if (block.has("text") && !block.get("text").isNull()) {
						thinking.append(block.getString("text"));
					}
				} else if ("tool_use".equals(type)) {
					String args = block.has("input") ? block.get("input").toString() : "{}";
					toolCalls.add(ToolCall.of(block.optString("id", null),
						block.optString("name", null), args));
				}
			}
		}
		String stopReason = resp.optString("stop_reason", null);
		ChatMessage message = ChatMessage.of(Role.ASSISTANT, text.length() > 0 ? text.toString() : null,
			null, null, null, toolCalls.isEmpty() ? null : toolCalls,
			thinking.length() > 0 ? thinking.toString() : null);
		List<Choice> choices = List.of(Choice.of(0, message, stopReason));
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			int in = u.optInt("input_tokens", 0);
			int out = u.optInt("output_tokens", 0);
			usage = TokenUsage.of(in, out, in + out);
		}
		return ChatResponse.of(id, model, choices, usage, rawJson);
	}

	// ==================== 模型列表 ====================

	@Override
	public List<Model> listModels() {
		JsonObject resp = doGet(MODELS_PATH);
		List<Model> models = new ArrayList<>();
		JsonArray data = resp.has("data") ? resp.getJsonArray("data") : null;
		if (data != null) {
			for (int i = 0; i < data.size(); i++) {
				JsonObject d = data.getJsonObject(i);
				models.add(Model.of(d.optString("id", null),
					d.has("created_at") ? d.get("created_at").getAsLong() : null,
					d.optString("display_name", null), d.optString("type", null), d.toString()));
			}
		}
		return models;
	}

	// ---------------------------------------------------------------------
	// 流式 SSE 解析（带命名事件）
	// ---------------------------------------------------------------------

	/**
	 * 自行实现流式 POST：使用 SseLineReader 获取命名事件，按事件类型分发。
	 *
	 * <p><b>注意：此路径直接使用 HttpClient，不经过基类的重试/熔断/指标机制。</b>
	 * （Anthropic 流式协议含命名事件，基类 doPostStream 不支持，故单独实现。）</p>
	 *
	 * @param body     请求体
	 * @param consumer 分片消费者
	 */
	private void streamMessages(JsonObject body, Consumer<ChatStreamChunk> consumer) {
		String url = resolveStreamUrl();
		String payload = Json.stringify(body);
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream")
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
		StreamState state = new StreamState();
		try (InputStream in = resp.body()) {
			SseLineReader.read(in, StandardCharsets.UTF_8, ev -> handleSseEvent(ev, state, consumer));
		} catch (IOException ex) {
			throw new AiException("stream read failed: " + ex.getMessage(), ex);
		}
	}

	/** 拼接流式 URL（resolveUrl 是 private，此处自行拼接）。 */
	private String resolveStreamUrl() {
		String base = this.config.baseUrl();
		if (base == null || base.isBlank()) {
			throw new AiException("baseUrl is not configured");
		}
		if (base.endsWith("/") && CHAT_PATH.startsWith("/")) {
			return base + CHAT_PATH.substring(1);
		}
		if (!base.endsWith("/") && !CHAT_PATH.startsWith("/")) {
			return base + "/" + CHAT_PATH;
		}
		return base + CHAT_PATH;
	}

	/** SSE 事件处理：按事件名分发到状态机。 */
	private void handleSseEvent(SseEvent ev, StreamState state, Consumer<ChatStreamChunk> consumer) {
		String event = ev.event();
		JsonObject data = Json.parse(ev.data()).getAsJsonObject();
		switch (event) {
			case "message_start": {
				JsonObject msg = data.getJsonObject("message");
				state.id = msg.optString("id", null);
				consumer.accept(ChatStreamChunk.of(state.id, Role.ASSISTANT, null, null, null));
				break;
			}
			case "content_block_start": {
				JsonObject block = data.getJsonObject("content_block");
				state.currentType = block.optString("type", "");
				if ("tool_use".equals(state.currentType)) {
					state.toolId = block.optString("id", null);
					state.toolName = block.optString("name", null);
					state.toolInputBuf = new StringBuilder();
				}
				break;
			}
			case "content_block_delta": {
				JsonObject delta = data.getJsonObject("delta");
				String deltaType = delta.optString("type", "");
				if ("text_delta".equals(deltaType) && delta.has("text")) {
					consumer.accept(ChatStreamChunk.of(state.id, null, delta.getString("text"), null, null));
				} else if ("input_json_delta".equals(deltaType) && delta.has("partial_json")) {
					if (state.toolInputBuf != null) {
						state.toolInputBuf.append(delta.getString("partial_json"));
					}
				}
				break;
			}
			case "content_block_stop": {
				if ("tool_use".equals(state.currentType) && state.toolInputBuf != null) {
					String args = state.toolInputBuf.toString();
					consumer.accept(ChatStreamChunk.of(state.id, null, null,
						List.of(ToolCall.of(state.toolId, state.toolName, args)), null));
					state.toolInputBuf = null;
				}
				state.currentType = null;
				break;
			}
			case "message_delta": {
				JsonObject delta = data.getJsonObject("delta");
				String stopReason = delta.optString("stop_reason", null);
				consumer.accept(ChatStreamChunk.of(state.id, null, null, null, stopReason));
				break;
			}
			case "message_stop":
				break;
			case "ping":
				break;
			default:
				break;
		}
	}

	/** 流式状态机内部状态。 */
	private static final class StreamState {
		private String id;
		private String currentType;
		private String toolId;
		private String toolName;
		private StringBuilder toolInputBuf;
	}
}
