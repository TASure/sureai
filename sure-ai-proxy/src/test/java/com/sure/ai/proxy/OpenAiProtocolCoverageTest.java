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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.gateway.RequestContext;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Role;

/**
 * {@link OpenAiProtocol} 直接单元测试：补齐协议编解码的边界分支
 * （私有构造器、非对象请求体、空白模型、全部可选参数、结构化 content、null id/model/usage）。
 *
 * <p>全部内存对象、零真实网络。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class OpenAiProtocolCoverageTest {

	/** 私有构造器不可实例化：反射调用应抛 {@link AssertionError}。 */
	@Test
	public void privateConstructorThrows() throws Exception {
		Constructor<OpenAiProtocol> ctor = OpenAiProtocol.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("expected AssertionError");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof AssertionError);
		}
	}

	/** 请求体是合法 JSON 但非对象（数组）：抛 AiException 映射 400。 */
	@Test
	public void parseBodyNonObjectThrows() {
		try {
			OpenAiProtocol.parseBody("[1,2,3]");
			fail("expected AiException");
		} catch (AiException e) {
			assertTrue(e.getMessage().contains("JSON object"));
		}
	}

	/** 请求体是合法 JSON 字符串字面量：同样非对象，抛 AiException。 */
	@Test
	public void parseBodyJsonStringThrows() {
		try {
			OpenAiProtocol.parseBody("\"hello\"");
			fail("expected AiException");
		} catch (AiException e) {
			assertTrue(e.getMessage().contains("JSON object"));
		}
	}

	/** 空白 model 回退默认模型；temperature/max_tokens/top_p/user 全部透传。 */
	@Test
	public void toCoreChatRequestAllOptionalFields() {
		String json = "{\"model\":\"   \","
			+ "\"temperature\":0.7,"
			+ "\"max_tokens\":128,"
			+ "\"top_p\":0.9,"
			+ "\"user\":\"u-1\","
			+ "\"stream\":true,"
			+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
		JsonObject body = OpenAiProtocol.parseBody(json);
		ChatRequest req = OpenAiProtocol.toCoreChatRequest(body, "gpt-default", "tenant-x");
		assertEquals("gpt-default", req.model());
		assertTrue(req.stream());
		assertEquals(0.7d, req.temperature(), 1e-9);
		assertEquals(Integer.valueOf(128), req.maxTokens());
		assertEquals(0.9d, req.topP(), 1e-9);
		assertEquals("u-1", req.user());
		assertEquals("tenant-x", req.extra().get(RequestContext.EXTRA_TENANT_ID));
	}

	/** messages 缺失或非数组：抛 AiException。 */
	@Test
	public void toCoreMessagesMissingArrayThrows() {
		JsonObject body = OpenAiProtocol.parseBody("{\"messages\":\"not-array\"}");
		try {
			OpenAiProtocol.toCoreChatRequest(body, "m", "t");
			fail("expected AiException");
		} catch (AiException e) {
			assertTrue(e.getMessage().contains("non-empty array"));
		}
	}

	/** messages 为空数组：抛 AiException。 */
	@Test
	public void toCoreMessagesEmptyThrows() {
		JsonObject body = OpenAiProtocol.parseBody("{\"messages\":[]}");
		try {
			OpenAiProtocol.toCoreChatRequest(body, "m", "t");
			fail("expected AiException");
		} catch (AiException e) {
			assertTrue(e.getMessage().contains("must not be empty"));
		}
	}

	/** content 缺失 → 空串；content 为结构化数组 → 序列化为字符串透传。 */
	@Test
	public void contentVariants() {
		JsonObject noContent = OpenAiProtocol.parseBody(
			"{\"messages\":[{\"role\":\"user\"}]}");
		ChatRequest r1 = OpenAiProtocol.toCoreChatRequest(noContent, "m", "t");
		assertEquals("", r1.messages().get(0).content());

		String json = "{\"messages\":[{\"role\":\"user\",\"content\":[\"part1\",\"part2\"]}]}";
		JsonObject structured = OpenAiProtocol.parseBody(json);
		ChatRequest r2 = OpenAiProtocol.toCoreChatRequest(structured, "m", "t");
		assertTrue(r2.messages().get(0).content().contains("part1"));
		assertTrue(r2.messages().get(0).content().contains("part2"));
	}

	/** 响应 id/model 为 null 时兜底；usage 为 null 时写零值。 */
	@Test
	public void chatCompletionJsonNullIdModelUsage() {
		Choice choice = Choice.of(0, ChatMessage.assistant("hi"), "stop");
		ChatResponse resp = ChatResponse.of(null, null, List.of(choice), null, "{}");
		String out = OpenAiProtocol.chatCompletionJson(resp);
		assertTrue(out.contains("\"id\":\"chatcmpl-"));
		assertTrue(out.contains("\"model\":\"\""));
		assertTrue(out.contains("\"prompt_tokens\":0"));
		assertTrue(out.contains("\"completion_tokens\":0"));
		assertTrue(out.contains("\"total_tokens\":0"));
	}

	/** 流式分片 id/model 为 null 时兜底。 */
	@Test
	public void streamChunkJsonNullIdModel() {
		ChatStreamChunk chunk = ChatStreamChunk.of(null, null, "delta", null, null);
		String out = OpenAiProtocol.streamChunkJson(chunk, null);
		assertTrue(out.contains("\"id\":\"chatcmpl-stream\""));
		assertTrue(out.contains("\"model\":\"\""));
		assertTrue(out.contains("\"delta\":{\"content\":\"delta\"}"));
	}

	/** embeddings 响应 model 为 null 时兜底空串。 */
	@Test
	public void embeddingsJsonNullModel() {
		EmbeddingResponse resp = EmbeddingResponse.of(null,
			List.of(new float[] { 0.1f, 0.2f }), null);
		String out = OpenAiProtocol.embeddingsJson(resp);
		assertTrue(out.contains("\"model\":\"\""));
		assertTrue(out.contains("\"embedding\":["));
		assertTrue(out.contains("\"object\":\"embedding\""));
	}

	/** modelsJson 输出对象列表。 */
	@Test
	public void modelsJsonFormat() {
		String out = OpenAiProtocol.modelsJson(List.of("gpt-4o", "embed-1"));
		assertTrue(out.contains("\"object\":\"list\""));
		assertTrue(out.contains("\"id\":\"gpt-4o\""));
		assertTrue(out.contains("\"owned_by\":\"sureai\""));
	}

	/** errorJson 输出 OpenAI 错误信封。 */
	@Test
	public void errorJsonFormat() {
		String out = OpenAiProtocol.errorJson("boom", "upstream_error", "gateway_error");
		JsonObject root = Json.parse(out).getAsJsonObject();
		JsonObject err = root.get("error").getAsJsonObject();
		assertEquals("boom", err.get("message").getAsString());
		assertEquals("upstream_error", err.get("type").getAsString());
		assertEquals("gateway_error", err.get("code").getAsString());
	}

	/** role 缺省为 user；assistant 角色正常序列化。 */
	@Test
	public void defaultRoleIsUser() {
		JsonObject body = OpenAiProtocol.parseBody(
			"{\"messages\":[{\"content\":\"hi\"}]}");
		ChatRequest req = OpenAiProtocol.toCoreChatRequest(body, "m", "t");
		assertEquals(Role.USER, req.messages().get(0).role());
		assertFalse(req.stream());
	}
}
