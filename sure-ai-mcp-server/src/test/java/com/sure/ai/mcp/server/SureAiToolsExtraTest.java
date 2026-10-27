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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ImageClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.ImageResult;

/**
 * {@link SureAiTools} 补测：覆盖 chat/embed/image 的错误分支、温度与 maxTokens 透传、
 * 坏角色回退、多客户端路由的空表/单表分支，以及私有构造器。
 *
 * @author sureai
 * @since 2.6.0
 */
public class SureAiToolsExtraTest {

	/** 可脚本化的假 AiClient。 */
	private static final class FakeChat implements AiClient {
		private final String reply;
		private final boolean emptyChoices;
		private final boolean fail;

		FakeChat(String reply, boolean emptyChoices, boolean fail) {
			this.reply = reply;
			this.emptyChoices = emptyChoices;
			this.fail = fail;
		}

		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			if (this.fail) {
				throw new IllegalStateException("chat boom");
			}
			if (this.emptyChoices) {
				return ChatResponse.of("id", request.model(), List.of(), null, null);
			}
			return ChatResponse.of("id", request.model(),
				List.of(Choice.of(0, ChatMessage.assistant(this.reply), "stop")), null, null);
		}

		@Override
		public void chatStream(ChatRequest request, java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> c) {
		}

		@Override
		public void close() {
		}
	}

	private static JsonObject args(String k1, Object v1) {
		JsonObject o = Json.object();
		o.set(k1, Json.toElement(v1));
		return o;
	}

	/** chat 缺 messages/prompt 返回错误。 */
	@Test
	public void chatMissingMessagesIsError() {
		McpServerTool tool = SureAiTools.chatTool(new FakeChat("x", false, false));
		assertTrue(tool.apply(args("model", "m")).isError());
	}

	/** chat 带 messages 数组与合法角色：正常解析。 */
	@Test
	public void chatMessagesWithValidRole() {
		FakeChat client = new FakeChat("back", false, false);
		McpServerTool tool = SureAiTools.chatTool(client);
		JsonObject msg = Json.object();
		msg.put("role", "user");
		msg.put("content", "hi");
		com.sure.ai.internal.json.JsonArray msgs = Json.array();
		msgs.add(msg);
		JsonObject args = Json.object();
		args.put("model", "m");
		args.set("messages", msgs);
		McpToolResult r = tool.apply(args);
		assertFalse(r.isError());
		assertEquals("back", r.asText());
	}

	/** chat messages 中角色非法时回退为 user。 */
	@Test
	public void chatMessagesWithBadRoleFallsBackToUser() {
		FakeChat client = new FakeChat("back", false, false);
		McpServerTool tool = SureAiTools.chatTool(client);
		JsonObject msg = Json.object();
		msg.put("role", "weird-role");
		msg.put("content", "hi");
		com.sure.ai.internal.json.JsonArray msgs = Json.array();
		msgs.add(msg);
		JsonObject args = Json.object();
		args.put("model", "m");
		args.set("messages", msgs);
		assertFalse(tool.apply(args).isError());
	}

	/** 多客户端注册表无 platform 时无法路由（>1 个 client 且未指定 platform）。 */
	@Test
	public void chatMultiClientWithoutPlatformIsError() {
		FakeChat alpha = new FakeChat("a", false, false);
		FakeChat beta = new FakeChat("b", false, false);
		McpServerTool tool = SureAiTools.chatTool(Map.of("alpha", alpha, "beta", beta));
		JsonObject args = Json.object();
		args.put("model", "m");
		args.put("prompt", "hi");
		assertTrue(tool.apply(args).isError());
	}

	/** chat 透传 temperature 与 maxTokens。 */
	@Test
	public void chatTemperatureAndMaxTokensPassedThrough() {
		FakeChat client = new FakeChat("back", false, false);
		McpServerTool tool = SureAiTools.chatTool(client);
		JsonObject args = Json.object();
		args.put("model", "m");
		args.put("prompt", "hi");
		args.set("temperature", Json.toElement(0.5));
		args.set("maxTokens", Json.toElement(128));
		assertFalse(tool.apply(args).isError());
	}

	/** chat 响应无文本时返回空串。 */
	@Test
	public void chatEmptyChoicesReturnsEmptyText() {
		McpServerTool tool = SureAiTools.chatTool(new FakeChat("", true, false));
		JsonObject args = Json.object();
		args.put("model", "m");
		args.put("prompt", "hi");
		McpToolResult r = tool.apply(args);
		assertFalse(r.isError());
		assertEquals("", r.asText());
	}

	/** chat 客户端抛异常时包装为 chat failed。 */
	@Test
	public void chatClientFailureIsError() {
		McpServerTool tool = SureAiTools.chatTool(new FakeChat("", false, true));
		JsonObject args = Json.object();
		args.put("model", "m");
		args.put("prompt", "hi");
		assertTrue(tool.apply(args).asText().contains("chat failed"));
	}

	/** chat 空注册表且无 platform → 无 client 可路由。 */
	@Test
	public void chatEmptyClientMapIsError() {
		McpServerTool tool = SureAiTools.chatTool(Map.of());
		assertTrue(tool.apply(args("model", "m")).isError());
	}

	/** embed 空注册表 → 无 client 可路由。 */
	@Test
	public void embedEmptyClientMapIsError() {
		McpServerTool tool = SureAiTools.embedTool(Map.of());
		assertTrue(tool.apply(args("model", "m")).isError());
	}

	/** embed 单客户端注册表无 platform 时直接选用。 */
	@Test
	public void embedSingleClientMapResolvesByDefault() {
		EmbeddingClient client = req -> EmbeddingResponse.of(req.model(),
			List.of(new float[] { 0.1f }), null);
		McpServerTool tool = SureAiTools.embedTool(Map.of("only", client));
		JsonObject args = Json.object();
		args.put("model", "emb");
		args.put("text", "hi");
		assertFalse(tool.apply(args).isError());
	}

	/** embed 用 input 数组分支。 */
	@Test
	public void embedInputArrayBranch() {
		EmbeddingClient client = req -> EmbeddingResponse.of(req.model(),
			List.of(new float[] { 0.1f, 0.2f }), null);
		McpServerTool tool = SureAiTools.embedTool(client);
		com.sure.ai.internal.json.JsonArray input = Json.array();
		input.add("a");
		input.add("b");
		JsonObject args = Json.object();
		args.put("model", "emb");
		args.set("input", input);
		assertFalse(tool.apply(args).isError());
	}

	/** embed 缺 model。 */
	@Test
	public void embedMissingModelIsError() {
		EmbeddingClient client = req -> EmbeddingResponse.of(req.model(), List.of(), null);
		McpServerTool tool = SureAiTools.embedTool(client);
		assertTrue(tool.apply(args("text", "hi")).isError());
	}

	/** embed 客户端异常。 */
	@Test
	public void embedClientFailureIsError() {
		EmbeddingClient client = (EmbeddingRequest req) -> {
			throw new IllegalStateException("embed boom");
		};
		McpServerTool tool = SureAiTools.embedTool(client);
		JsonObject args = Json.object();
		args.put("model", "emb");
		args.put("text", "hi");
		assertTrue(tool.apply(args).asText().contains("embed failed"));
	}

	/** image 空注册表 → 无 client 可路由。 */
	@Test
	public void imageEmptyClientMapIsError() {
		McpServerTool tool = SureAiTools.imageTool(Map.of());
		assertTrue(tool.apply(args("model", "dall")).isError());
	}

	/** image 返回 b64 时直接回 b64。 */
	@Test
	public void imageReturnsB64() {
		ImageClient client = req -> ImageResponse.of(1L,
			List.of(ImageResult.ofB64("cHJldGVuZGVk")), null);
		McpServerTool tool = SureAiTools.imageTool(client);
		JsonObject args = Json.object();
		args.put("model", "dall");
		args.put("prompt", "cat");
		assertEquals("cHJldGVuZGVk", tool.apply(args).asText());
	}

	/** image 无任何结果时返回 no image produced。 */
	@Test
	public void imageNoResultIsError() {
		ImageClient client = req -> ImageResponse.of(0L, List.of(), null);
		McpServerTool tool = SureAiTools.imageTool(client);
		JsonObject args = Json.object();
		args.put("model", "dall");
		args.put("prompt", "cat");
		assertTrue(tool.apply(args).asText().contains("no image produced"));
	}

	/** image 客户端异常。 */
	@Test
	public void imageClientFailureIsError() {
		ImageClient client = (ImageRequest req) -> {
			throw new IllegalStateException("image boom");
		};
		McpServerTool tool = SureAiTools.imageTool(client);
		JsonObject args = Json.object();
		args.put("model", "dall");
		args.put("prompt", "cat");
		assertTrue(tool.apply(args).asText().contains("image failed"));
	}

	/** 私有构造器应抛 AssertionError。 */
	@Test
	public void privateConstructorThrows() {
		try {
			var ctor = SureAiTools.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			ctor.newInstance();
			assertTrue("应抛 AssertionError", false);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			assertEquals(AssertionError.class, ex.getCause().getClass());
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}
}
