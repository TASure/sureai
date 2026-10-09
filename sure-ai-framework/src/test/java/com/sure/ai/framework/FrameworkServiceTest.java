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

package com.sure.ai.framework;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sure.ai.exception.AiException;
import com.sure.ai.framework.annotation.AiService;
import com.sure.ai.framework.annotation.Memory;
import com.sure.ai.framework.annotation.Param;
import com.sure.ai.framework.annotation.SystemMessage;
import com.sure.ai.framework.annotation.Tool;
import com.sure.ai.framework.annotation.UserMessage;
import com.sure.ai.framework.memory.InMemoryChatMemory;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.ToolSpec;

import org.junit.Test;

/**
 * {@link FrameworkUtil} 声明式代理的单元测试（零真实网络）。
 *
 * @author sureai
 * @since 2.5.0
 */
public class FrameworkServiceTest {

	/** 基础服务：系统/用户模板、单参直传、原始响应、流式。 */
	@AiService(model = "test-model")
	interface Greeter {

		@SystemMessage("你是一个{role}助手")
		@UserMessage("请回答：{q}")
		String chat(String role, String q);

		String echo(String input);

		ChatResponse raw(String input);

		Stream<ChatStreamChunk> stream(String input);
	}

	/** 结构化输出：record 返回。 */
	record Person(String name, int age) {
	}

	interface Structured {

		@UserMessage("生成一个人物")
		Person person(String dummy);
	}

	/** 带 @Tool 方法的服务。 */
	interface Tooled {

		@UserMessage("今天天气如何")
		String ask(String q);

		@Tool(description = "计算两数之和")
		int add(int a, int b);
	}

	/** 会话记忆服务（类级 @Memory）。 */
	@AiService(model = "m")
	@Memory
	interface MemChat {

		String talk(String msg);
	}

	/** 缺模型：既无 @AiService.model 也无 builder.model。 */
	interface NoModel {
		String hi(String q);
	}

	/** 多参数但无 @UserMessage 模板（非法）。 */
	interface BadMultiParam {
		String join(String a, String b);
	}

	/** 不支持的返回类型（非法）。 */
	interface BadReturn {
		List<String> weird(String q);
	}

	/** 使用 @Param 显式绑定。 */
	@AiService(model = "m")
	interface ParamService {
		@UserMessage("你好，{who}")
		String hello(@Param("who") String visitor);
	}

	@Test
	public void testBlockingStringWithTemplates() {
		FakeAiClient client = new FakeAiClient().withText("好的");
		Greeter ai = FrameworkUtil.create(Greeter.class, client);
		String out = ai.chat("法务", "合同里的违约金怎么算");
		assertEquals("好的", out);

		ChatMessage system = client.last().messages().get(0);
		assertEquals("system", system.role().value());
		assertEquals("你是一个法务助手", system.content());

		ChatMessage user = client.last().messages().get(1);
		assertEquals("user", user.role().value());
		assertEquals("请回答：合同里的违约金怎么算", user.content());
		assertEquals("test-model", client.last().model());
	}

	@Test
	public void testSingleParamNoAnnotationIsDirectUser() {
		FakeAiClient client = new FakeAiClient().withText("回显");
		Greeter ai = FrameworkUtil.create(Greeter.class, client);
		assertEquals("回显", ai.echo("原始输入"));
		assertEquals(1, client.last().messages().size());
		assertEquals("原始输入", client.last().messages().get(0).content());
	}

	@Test
	public void testRawChatResponseReturn() {
		FakeAiClient client = new FakeAiClient().withText("原始");
		Greeter ai = FrameworkUtil.create(Greeter.class, client);
		ChatResponse resp = ai.raw("hi");
		assertEquals("原始", resp.firstText());
	}

	@Test
	public void testStreamReturn() {
		FakeAiClient client = new FakeAiClient()
			.withChunks(FakeAiClient.chunk("你"), FakeAiClient.chunk("好"));
		Greeter ai = FrameworkUtil.create(Greeter.class, client);
		List<String> delta = ai.stream("hi").map(ChatStreamChunk::deltaText).collect(Collectors.toList());
		assertEquals(List.of("你", "好"), delta);
		assertTrue("流式请求应标记为 stream", client.last().stream());
	}

	@Test
	public void testStructuredRecordReturn() {
		FakeAiClient client = new FakeAiClient().withText("{\"name\":\"张三\",\"age\":30}");
		Structured ai = FrameworkUtil.create(Structured.class, client, "pmodel");
		Person p = ai.person("x");
		assertEquals("张三", p.name());
		assertEquals(30, p.age());

		Object rf = client.last().responseFormat();
		assertNotNull(rf);
		JsonObject obj = (JsonObject) rf;
		assertEquals("json_schema", obj.getString("type"));
	}

	@Test
	public void testToolMethodRegisteredIntoRequest() {
		FakeAiClient client = new FakeAiClient().withText("晴");
		Tooled ai = FrameworkUtil.create(Tooled.class, client, "tm");
		assertEquals("晴", ai.ask("北京"));

		List<ToolSpec> tools = client.last().tools();
		assertNotNull(tools);
		assertEquals(1, tools.size());
		ToolSpec spec = tools.get(0);
		assertEquals("add", spec.function().name());
		assertEquals("计算两数之和", spec.function().description());
		assertTrue(spec.function().parameters().contains("\"a\""));
		assertTrue(spec.function().parameters().contains("\"b\""));
	}

	@Test
	public void testDirectToolMethodCallRejected() {
		FakeAiClient client = new FakeAiClient();
		Tooled ai = FrameworkUtil.create(Tooled.class, client, "tm");
		assertThrows(IllegalStateException.class, () -> ai.add(1, 2));
	}

	@Test
	public void testMemoryInjectsHistoryAndAppendsRound() {
		FakeAiClient client = new FakeAiClient().withText("回复1").withText("回复2");
		InMemoryChatMemory memory = new InMemoryChatMemory();
		MemChat ai = FrameworkUtil.builder(MemChat.class, client).memory(memory).build();

		ai.talk("你好1");
		assertEquals(2, memory.size());

		ai.talk("你好2");
		List<ChatMessage> msgs = client.last().messages();
		// 历史：user(你好1), assistant(回复1) + 当前 user(你好2)
		assertEquals(3, msgs.size());
		assertEquals("user", msgs.get(0).role().value());
		assertEquals("你好1", msgs.get(0).content());
		assertEquals("assistant", msgs.get(1).role().value());
		assertEquals("回复1", msgs.get(1).content());
		assertEquals("user", msgs.get(2).role().value());
		assertEquals("你好2", msgs.get(2).content());
		assertEquals(4, memory.size());
	}

	@Test
	public void testExplicitBuilderModelAndTemperature() {
		FakeAiClient client = new FakeAiClient().withText("x");
		NoModel ai = FrameworkUtil.builder(NoModel.class, client).model("custom-model").build();
		ai.hi("q");
		assertEquals("custom-model", client.last().model());
	}

	@Test
	public void testParamAnnotationBinding() {
		FakeAiClient client = new FakeAiClient().withText("ok");
		ParamService ai = FrameworkUtil.create(ParamService.class, client);
		ai.hello("小明");
		assertEquals("你好，小明", client.last().messages().get(0).content());
	}

	@Test
	public void testValidationNoModel() {
		FakeAiClient client = new FakeAiClient();
		assertThrows(AiException.class, () -> FrameworkUtil.create(NoModel.class, client));
	}

	@Test
	public void testValidationMultiParamWithoutTemplate() {
		FakeAiClient client = new FakeAiClient();
		assertThrows(AiException.class, () -> FrameworkUtil.create(BadMultiParam.class, client, "m"));
	}

	@Test
	public void testValidationUnsupportedReturnType() {
		FakeAiClient client = new FakeAiClient();
		assertThrows(AiException.class, () -> FrameworkUtil.create(BadReturn.class, client, "m"));
	}

	@Test
	public void testRejectsNonInterface() {
		FakeAiClient client = new FakeAiClient();
		assertThrows(AiException.class, () -> FrameworkUtil.create(String.class, client));
	}
}
