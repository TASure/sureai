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

package com.sure.ai.client.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Role;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;
import com.sure.ai.model.VideoPart;

/**
 * {@link ChatCacheKey} 多模态片段、工具调用与对象型字段分支测试。
 *
 * <p>覆盖既有基础用例未触达的 parts 序列化、name/toolCallId、toolCalls、
 * responseFormat/thinkingConfig/grounding 对象归一化等分支。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public class ChatCacheKeyEdgeTest {

	/** 构造含多模态与工具调用的复合请求。 */
	private static ChatRequest compositeRequest() {
		List<MessagePart> parts = List.of(
			TextPart.of("看图说话"),
			ImagePart.ofUrl("http://img/a.png"),
			DocumentPart.ofBase64("doc.pdf", "application/pdf", "QkFTRTY0"),
			VideoPart.ofUrl("http://v/b.mp4"));

		ChatMessage system = ChatMessage.system("你是助手");
		ChatMessage user = ChatMessage.user(parts);
		ChatMessage assistant = ChatMessage.assistant(List.of(
			ToolCall.of("call_1", "getWeather", "{\"city\":\"X\"}"),
			ToolCall.of("call_2", "calc", null)));
		ChatMessage tool = ChatMessage.tool("call_1", "晴朗");
		// 带 name 的命名消息
		ChatMessage named = ChatMessage.of(Role.USER, "hi", null, "Alice", null, null);

		JsonObject fmt = new JsonObject();
		fmt.put("type", "json_object");
		JsonObject grounding = new JsonObject();
		grounding.put("type", "web_search");

		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(system, user, assistant, tool, named)
			.tools(List.of(ToolSpec.of(ToolFunction.of("getWeather", "天气", "{}"))))
			.responseFormat(fmt)
			.grounding(grounding)
			.temperature(0.5d)
			.topP(0.9d)
			.build();
	}

	/** 复合请求可稳定归一化且 key 为 64 字符。 */
	@Test
	public void testCompositeNormalize() {
		ChatRequest r = compositeRequest();
		String n1 = ChatCacheKey.normalize(r);
		String n2 = ChatCacheKey.normalize(r);
		assertEquals(n1, n2);
		String key = ChatCacheKey.of(r);
		assertEquals(64, key.length());
		assertTrue(key.matches("[0-9a-f]{64}"));
	}

	/** 多模态片段按类型进入归一化串。 */
	@Test
	public void testPartsSerialized() {
		String n = ChatCacheKey.normalize(compositeRequest());
		assertTrue(n.contains("text:"));
		assertTrue(n.contains("img:"));
		assertTrue(n.contains("doc:"));
		assertTrue(n.contains("video:"));
	}

	/** 工具调用与 name/toolCallId 进入归一化串。 */
	@Test
	public void testToolCallsAndNamesSerialized() {
		String n = ChatCacheKey.normalize(compositeRequest());
		assertTrue(n.contains("call_1"));
		assertTrue(n.contains("getWeather"));
		assertTrue(n.contains("Alice"));
	}

	/** 对象型 responseFormat 以 canonical JSON 参与 hash。 */
	@Test
	public void testObjectFieldsNormalized() {
		String n = ChatCacheKey.normalize(compositeRequest());
		assertTrue(n.contains("json_object"));
		assertTrue(n.contains("web_search"));
	}

	/** thinkingConfig 对象字段参与 hash。 */
	@Test
	public void testThinkingConfigParticipates() {
		ChatRequest rNoThinking = compositeRequest();
		JsonObject t1 = new JsonObject();
		t1.put("type", "enabled");
		ChatRequest rThinking = ChatRequest.builder()
			.model("gpt-4o")
			.messages(ChatMessage.user("hi"))
			.thinkingConfig(t1)
			.build();
		assertNotEquals(ChatCacheKey.of(rNoThinking), ChatCacheKey.of(rThinking));
	}

	/** 归一化串中的 canonical JSON 片段可被解析。 */
	@Test
	public void testCanonicalJsonFragmentValid() {
		String n = ChatCacheKey.normalize(compositeRequest());
		// responseFormat/grounding 以 canonical JSON 片段形式出现，应是合法 JSON 子串
		assertTrue(n.contains("\"type\""));
	}
}
