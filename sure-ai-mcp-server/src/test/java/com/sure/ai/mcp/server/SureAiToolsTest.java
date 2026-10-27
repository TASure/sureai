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
import java.util.concurrent.atomic.AtomicReference;

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
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.ImageResult;

/**
 * {@link SureAiTools} 工厂测试：用 fake 客户端验证路由、响应包装与错误→isError。
 *
 * @author sureai
 * @since 1.5.0
 */
public class SureAiToolsTest {

	private static final class FakeChat implements AiClient {
		private final AtomicReference<ChatRequest> captured = new AtomicReference<>();
		private final String reply;

		FakeChat(String reply) {
			this.reply = reply;
		}

		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.captured.set(request);
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

	private static JsonObject args(String kv1, Object v1) {
		JsonObject o = Json.object();
		o.set(kv1, Json.toElement(v1));
		return o;
	}

	@Test
	public void chatToolRoutesAndWrapsText() {
		FakeChat client = new FakeChat("hello-back");
		McpServerTool tool = SureAiTools.chatTool(client);
		assertEquals("sureai.chat", tool.name());
		assertTrue(tool.inputSchema().has("properties"));

		JsonObject a = Json.object();
		a.put("model", "gpt-x");
		a.put("prompt", "hi");
		McpToolResult r = tool.apply(a);
		assertFalse(r.isError());
		assertEquals("hello-back", r.asText());
		assertEquals("gpt-x", client.captured.get().model());
	}

	@Test
	public void chatToolMissingModelIsError() {
		McpServerTool tool = SureAiTools.chatTool(new FakeChat("x"));
		McpToolResult r = tool.apply(args("prompt", "hi"));
		assertTrue(r.isError());
	}

	@Test
	public void chatToolMultiClientRoutesByPlatform() {
		FakeChat alpha = new FakeChat("alpha-reply");
		FakeChat beta = new FakeChat("beta-reply");
		McpServerTool tool = SureAiTools.chatTool(Map.of("alpha", alpha, "beta", beta));

		JsonObject a = Json.object();
		a.put("platform", "beta");
		a.put("model", "m");
		a.put("prompt", "hi");
		McpToolResult r = tool.apply(a);
		assertFalse(r.isError());
		assertEquals("beta-reply", r.asText());
	}

	@Test
	public void chatToolUnknownPlatformIsError() {
		McpServerTool tool = SureAiTools.chatTool(Map.of("alpha", new FakeChat("a")));
		JsonObject a = Json.object();
		a.put("platform", "ghost");
		a.put("model", "m");
		a.put("prompt", "hi");
		assertTrue(tool.apply(a).isError());
	}

	@Test
	public void embedToolSerializesVector() {
		EmbeddingClient client = req -> EmbeddingResponse.of(req.model(),
			List.of(new float[] { 0.1f, 0.2f }), null);
		McpServerTool tool = SureAiTools.embedTool(client);
		JsonObject a = Json.object();
		a.put("model", "emb");
		a.put("text", "hello");
		McpToolResult r = tool.apply(a);
		assertFalse(r.isError());
		assertTrue(r.asText().contains("\"embeddings\""));
	}

	@Test
	public void embedToolMissingInputIsError() {
		EmbeddingClient client = req -> EmbeddingResponse.of(req.model(), List.of(), null);
		McpServerTool tool = SureAiTools.embedTool(client);
		assertTrue(tool.apply(args("model", "emb")).isError());
	}

	@Test
	public void imageToolReturnsUrl() {
		ImageClient client = req -> ImageResponse.of(1L,
			List.of(ImageResult.ofUrl("http://img/u.png")), null);
		McpServerTool tool = SureAiTools.imageTool(client);
		JsonObject a = Json.object();
		a.put("model", "dall");
		a.put("prompt", "a cat");
		McpToolResult r = tool.apply(a);
		assertFalse(r.isError());
		assertEquals("http://img/u.png", r.asText());
	}

	@Test
	public void imageToolMissingPromptIsError() {
		ImageClient client = req -> ImageResponse.of(0L, List.of(), null);
		McpServerTool tool = SureAiTools.imageTool(client);
		assertTrue(tool.apply(args("model", "dall")).isError());
	}
}
