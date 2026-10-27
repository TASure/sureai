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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ImageClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.mcp.server.tool.ChatToolRequest;
import com.sure.ai.mcp.server.tool.EmbedToolRequest;
import com.sure.ai.mcp.server.tool.ImageToolRequest;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.Role;
import com.sure.ai.util.JsonSchemaGenerator;

/**
 * 预置 MCP 工具工厂：把 sureai 的 {@link AiClient}/{@link EmbeddingClient}/{@link ImageClient}
 * 包成 {@link McpServerTool}。
 *
 * <p><b>平台隔离</b>：本类只依赖 core 的客户端抽象与模型，不 import 任何具体平台模块；
 * 具体客户端由调用方传入。多客户端时用 {@code Map<String, AiClient>} 按 {@code platform} 参数路由。</p>
 *
 * <p>RAG 查询工具不在本核心实现；如需暴露 RAG，请在 {@code sure-ai-rag} 内仿照本工厂编写适配类，
 * 把检索结果包成 {@link McpToolResult} 后 {@code registerTool}。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class SureAiTools {

	private SureAiTools() {
		throw new AssertionError("No instances");
	}

	// ==================== chat ====================

	/**
	 * 基于单个 {@link AiClient} 构建 chat 工具。
	 *
	 * @param client 对话客户端
	 * @return MCP 工具
	 */
	public static McpServerTool chatTool(AiClient client) {
		return chatTool(Map.of(), client);
	}

	/**
	 * 基于客户端注册表构建 chat 工具（按 {@code platform} 路由）。
	 *
	 * @param clients 客户端注册表，key 为平台名
	 * @return MCP 工具
	 */
	public static McpServerTool chatTool(Map<String, AiClient> clients) {
		return chatTool(clients, null);
	}

	private static McpServerTool chatTool(Map<String, AiClient> clients, AiClient single) {
		return new McpServerTool("sureai.chat",
			"调用大模型对话：传入 model 与 messages（或单条 prompt），返回助手文本。",
			JsonSchemaGenerator.generate(ChatToolRequest.class),
			args -> handleChat(args, clients, single));
	}

	private static McpToolResult handleChat(JsonObject args, Map<String, AiClient> clients, AiClient single) {
		try {
			AiClient client = resolve(clients, single, args.optString("platform", null));
			if (client == null) {
				return err("no AiClient resolved");
			}
			String model = args.optString("model", null);
			if (model == null || model.isEmpty()) {
				return err("missing model");
			}
			List<ChatMessage> messages = toMessages(args);
			if (messages.isEmpty()) {
				return err("missing messages/prompt");
			}
			ChatRequest.Builder b = ChatRequest.builder().model(model).messages(messages);
			if (args.has("temperature") && args.get("temperature").isNumber()) {
				b.temperature(args.get("temperature").getAsDouble());
			}
			if (args.has("maxTokens") && args.get("maxTokens").isNumber()) {
				b.maxTokens(args.get("maxTokens").getAsInt());
			}
			ChatResponse resp = client.chat(b.build());
			String text = resp.firstText();
			return new McpToolResult(false, List.of(text == null ? "" : text));
		} catch (RuntimeException ex) {
			return err("chat failed: " + ex.getMessage());
		}
	}

	private static List<ChatMessage> toMessages(JsonObject args) {
		List<ChatMessage> out = new ArrayList<>();
		if (args.has("messages") && args.get("messages").isArray()) {
			JsonArray arr = args.get("messages").getAsJsonArray();
			for (int i = 0; i < arr.size(); i++) {
				JsonObject m = arr.get(i).getAsJsonObject();
				String roleStr = m.optString("role", "user");
				String content = m.optString("content", "");
				Role role;
				try {
					role = Role.fromValue(roleStr);
				} catch (RuntimeException ex) {
					role = Role.USER;
				}
				out.add(ChatMessage.of(role, content, null, null, null, null));
			}
		}
		if (out.isEmpty() && args.has("prompt") && args.get("prompt").isString()) {
			out.add(ChatMessage.user(args.getString("prompt")));
		}
		return out;
	}

	// ==================== embed ====================

	/**
	 * 基于单个 {@link EmbeddingClient} 构建 embed 工具。
	 *
	 * @param client 向量客户端
	 * @return MCP 工具
	 */
	public static McpServerTool embedTool(EmbeddingClient client) {
		return embedTool(Map.of(), client);
	}

	/**
	 * 基于客户端注册表构建 embed 工具。
	 *
	 * @param clients 向量客户端注册表
	 * @return MCP 工具
	 */
	public static McpServerTool embedTool(Map<String, EmbeddingClient> clients) {
		return embedTool(clients, null);
	}

	private static McpServerTool embedTool(Map<String, EmbeddingClient> clients, EmbeddingClient single) {
		return new McpServerTool("sureai.embed",
			"把文本向量化：传入 model 与 input（文本数组）或单条 text，返回向量 JSON。",
			JsonSchemaGenerator.generate(EmbedToolRequest.class),
			args -> handleEmbed(args, clients, single));
	}

	private static McpToolResult handleEmbed(JsonObject args, Map<String, EmbeddingClient> clients,
			EmbeddingClient single) {
		try {
			EmbeddingClient client = resolve(clients, single, args.optString("platform", null));
			if (client == null) {
				return err("no EmbeddingClient resolved");
			}
			String model = args.optString("model", null);
			if (model == null || model.isEmpty()) {
				return err("missing model");
			}
			List<String> texts = new ArrayList<>();
			if (args.has("input") && args.get("input").isArray()) {
				JsonArray arr = args.get("input").getAsJsonArray();
				for (int i = 0; i < arr.size(); i++) {
					texts.add(arr.get(i).getAsString());
				}
			} else if (args.has("text") && args.get("text").isString()) {
				texts.add(args.getString("text"));
			}
			if (texts.isEmpty()) {
				return err("missing input/text");
			}
			EmbeddingResponse resp = client.embed(new EmbeddingRequest(model, texts));
			return new McpToolResult(false, List.of(Json.stringify(embedToJson(resp))));
		} catch (RuntimeException ex) {
			return err("embed failed: " + ex.getMessage());
		}
	}

	private static JsonObject embedToJson(EmbeddingResponse resp) {
		JsonObject o = Json.object();
		o.put("model", resp.model());
		JsonArray vecs = Json.array();
		for (float[] vec : resp.embeddings()) {
			JsonArray row = Json.array();
			for (float v : vec) {
				row.add((double) v);
			}
			vecs.add(row);
		}
		o.set("embeddings", vecs);
		return o;
	}

	// ==================== image ====================

	/**
	 * 基于单个 {@link ImageClient} 构建 image 工具。
	 *
	 * @param client 图像客户端
	 * @return MCP 工具
	 */
	public static McpServerTool imageTool(ImageClient client) {
		return imageTool(Map.of(), client);
	}

	/**
	 * 基于客户端注册表构建 image 工具。
	 *
	 * @param clients 图像客户端注册表
	 * @return MCP 工具
	 */
	public static McpServerTool imageTool(Map<String, ImageClient> clients) {
		return imageTool(clients, null);
	}

	private static McpServerTool imageTool(Map<String, ImageClient> clients, ImageClient single) {
		return new McpServerTool("sureai.image",
			"生成图像：传入 model 与 prompt，返回图像 URL 或 Base64。",
			JsonSchemaGenerator.generate(ImageToolRequest.class),
			args -> handleImage(args, clients, single));
	}

	private static McpToolResult handleImage(JsonObject args, Map<String, ImageClient> clients, ImageClient single) {
		try {
			ImageClient client = resolve(clients, single, args.optString("platform", null));
			if (client == null) {
				return err("no ImageClient resolved");
			}
			String model = args.optString("model", null);
			String prompt = args.optString("prompt", null);
			if (model == null || model.isEmpty() || prompt == null || prompt.isEmpty()) {
				return err("missing model/prompt");
			}
			ImageResponse resp = client.generate(ImageRequest.of(model, prompt));
			String url = resp.firstUrl();
			String b64 = resp.firstB64();
			if (url != null) {
				return new McpToolResult(false, List.of(url));
			}
			if (b64 != null) {
				return new McpToolResult(false, List.of(b64));
			}
			return err("no image produced");
		} catch (RuntimeException ex) {
			return err("image failed: " + ex.getMessage());
		}
	}

	// ==================== 路由辅助 ====================

	private static <T> T resolve(Map<String, T> map, T single, String platform) {
		if (single != null) {
			return single;
		}
		if (map == null || map.isEmpty()) {
			return null;
		}
		if (platform != null && !platform.isEmpty()) {
			return map.get(platform);
		}
		if (map.size() == 1) {
			return map.values().iterator().next();
		}
		return null;
	}

	private static McpToolResult err(String msg) {
		return new McpToolResult(true, List.of(msg));
	}
}
