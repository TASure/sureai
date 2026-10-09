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

package com.sure.ai.framework.advisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

import org.junit.Test;

/**
 * {@link StructuredOutputValidationAdvisor} 自纠重试测试（零真实网络）。
 *
 * @author sureai
 * @since 2.5.0
 */
public class StructuredOutputValidationAdvisorTest {

	/** 目标 record。 */
	public record Person(String name, int age) {
	}

	private static AdvisorContext ctx() {
		ChatRequest req = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("生成人物"))).build();
		return new AdvisorContext(req, null, Person.class);
	}

	@Test
	public void firstInvalidThenSelfCorrectedRetrySucceeds() {
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.text("哎呀我乱答的"))
			.then(ScriptedClient.text("{\"name\":\"张三\",\"age\":30}"));

		AdvisorChain chain = new AdvisorChain(List.of(new StructuredOutputValidationAdvisor(2)),
			c -> client.chat(c.rebuildRequest()));
		ChatResponse resp = chain.execute(ctx());

		assertEquals("{\"name\":\"张三\",\"age\":30}", resp.firstText());
		assertEquals(2, client.callCount());
		// 第二次请求应附带一条修正指令消息
		List<ChatMessage> msgs = client.requests().get(1).messages();
		assertEquals(2, msgs.size());
		assertTrue("修正指令应回灌模型: " + msgs.get(1).content(),
			msgs.get(1).content().contains("不符合要求"));
	}

	@Test
	public void exhaustRetriesReturnsLastResponse() {
		ScriptedClient client = new ScriptedClient()
			.repeat(ScriptedClient.text("还是不对"));

		AdvisorChain chain = new AdvisorChain(List.of(new StructuredOutputValidationAdvisor(1)),
			c -> client.chat(c.rebuildRequest()));
		ChatResponse resp = chain.execute(ctx());

		assertEquals("还是不对", resp.firstText());
		assertEquals("首调 + 1 次重试 = 2", 2, client.callCount());
	}

	@Test
	public void noExpectedTypePassesThrough() {
		// expectedType==null：advisor 不介入，原样透传
		ScriptedClient client = new ScriptedClient().then(ScriptedClient.text("自由文本"));
		ChatRequest req = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi"))).build();
		AdvisorContext ctx = new AdvisorContext(req, null, null);
		AdvisorChain chain = new AdvisorChain(List.of(new StructuredOutputValidationAdvisor()),
			c -> client.chat(c.rebuildRequest()));

		ChatResponse resp = chain.execute(ctx);
		assertEquals("自由文本", resp.firstText());
		assertEquals(1, client.callCount());
	}
}
