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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.framework.cache.SemanticCache;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ToolCall;

/**
 * advisor 包内覆盖率补测：ToolCallingAdvisor 安全执行 / StructuredOutputValidationAdvisor 重试 /
 * SemanticCacheAdvisor 透传与 put 异常吞噬。
 *
 * <p>使用包内可见的 {@link ScriptedClient}，零真实网络。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class FrameworkAdvisorCoverageExtraTest {

	/** 构造一个基础请求。 */
	private static ChatRequest baseRequest() {
		return ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi"))).build();
	}

	// ==================== ToolCallingAdvisor 边界 ====================

	/** maxIterations<1 抛异常。 */
	@Test
	public void toolCallingAdvisorRejectsMaxIterationsBelowOne() {
		assertThrows(RuntimeException.class, () -> new ToolCallingAdvisor(0));
	}

	/** executor.execute 抛异常时收敛为错误文本回灌模型。 */
	@Test
	public void toolCallingAdvisorSafeExecuteCatch() {
		ToolExecutor throwing = (name, args) -> {
			throw new IllegalStateException("exec boom");
		};
		ToolCall call = ToolCall.of("1", "x", "{}");
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.toolCalls(List.of(call)))
			.then(ScriptedClient.text("final"));
		AdvisorContext ctx = new AdvisorContext(baseRequest(), throwing, null);
		AdvisorChain chain = new AdvisorChain(List.of(new ToolCallingAdvisor(3)),
			c -> client.chat(c.rebuildRequest()));
		ChatResponse resp = chain.execute(ctx);
		assertEquals("final", resp.firstText());
		assertEquals(2, client.callCount());
		String toolResult = client.requests().get(1).messages().get(2).content();
		assertTrue(toolResult.contains("exec boom"));
	}

	// ==================== StructuredOutputValidationAdvisor 边界 ====================

	/** maxRetries<0 抛异常。 */
	@Test
	public void structuredValidationRejectsNegativeRetries() {
		assertThrows(RuntimeException.class, () -> new StructuredOutputValidationAdvisor(-1));
	}

	/** expectedType 为 null 时透传。 */
	@Test
	public void structuredValidationSkipsWhenNoExpectedType() {
		StructuredOutputValidationAdvisor advisor = new StructuredOutputValidationAdvisor();
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, null);
		AdvisorChain chain = new AdvisorChain(List.of(advisor), c -> ScriptedClient.text("raw"));
		ChatResponse resp = chain.execute(ctx);
		assertEquals("raw", resp.firstText());
	}

	/** 输出非 JSON 对象时校验失败并重试。 */
	@Test
	public void structuredValidationRetriesOnInvalidJson() {
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.text("not json"))
			.then(ScriptedClient.text("{\"name\":\"ok\"}"));
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, NamedRecord.class);
		AdvisorChain chain = new AdvisorChain(List.of(new StructuredOutputValidationAdvisor(2)),
			c -> client.chat(c.rebuildRequest()));
		ChatResponse resp = chain.execute(ctx);
		assertEquals("{\"name\":\"ok\"}", resp.firstText());
		assertEquals(2, client.callCount());
	}

	// ==================== SemanticCacheAdvisor 边界 ====================

	/** 无 user 文本时透传。 */
	@Test
	public void semanticCacheAdvisorPassesThroughWhenNoUserText() {
		SemanticCache cache = new SemanticCache() {
			@Override public ChatResponse get(String q) { return null; }
			@Override public void put(String q, ChatResponse r, long ttl) { }
			@Override public void remove(String q) { }
			@Override public void clear() { }
		};
		SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(cache);
		ChatRequest b = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.system("sys"))).build();
		AdvisorContext ctx = new AdvisorContext(b, null, null);
		AdvisorChain chain = new AdvisorChain(List.of(advisor), c -> ScriptedClient.text("ok"));
		assertEquals("ok", chain.execute(ctx).firstText());
	}

	/** put 抛异常时不影响主结果。 */
	@Test
	public void semanticCacheAdvisorPutExceptionIsSwallowed() {
		SemanticCache cache = new SemanticCache() {
			@Override public ChatResponse get(String q) { return null; }
			@Override public void put(String q, ChatResponse r, long ttl) {
				throw new RuntimeException("store down");
			}
			@Override public void remove(String q) { }
			@Override public void clear() { }
		};
		SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(cache);
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, null);
		AdvisorChain chain = new AdvisorChain(List.of(advisor), c -> ScriptedClient.text("ok"));
		assertEquals("ok", chain.execute(ctx).firstText());
	}

	/** 缓存命中且查询超长时触发 preview 截断日志。 */
	@Test
	public void semanticCacheAdvisorHitWithLongQuery() {
		String longQuery = "这是一个非常非常非常非常非常非常非常非常非常非常长的用户查询";
		ChatResponse cached = ScriptedClient.text("cached-answer");
		SemanticCache cache = new SemanticCache() {
			@Override public ChatResponse get(String q) { return cached; }
			@Override public void put(String q, ChatResponse r, long ttl) { }
			@Override public void remove(String q) { }
			@Override public void clear() { }
		};
		SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(cache);
		ChatRequest b = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user(longQuery))).build();
		AdvisorContext ctx = new AdvisorContext(b, null, null);
		AdvisorChain chain = new AdvisorChain(List.of(advisor), c -> ScriptedClient.text("should-not-hit"));
		ChatResponse resp = chain.execute(ctx);
		assertEquals("cached-answer", resp.firstText());
	}

	/** 终端返回 null 时安全降级。 */
	@Test
	public void toolCallingAdvisorNullResponse() {
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, null);
		AdvisorChain chain = new AdvisorChain(List.of(new ToolCallingAdvisor(3)), c -> null);
		assertNull(chain.execute(ctx));
	}

	/** 结构化输出校验：空文本触发重试路径。 */
	@Test
	public void structuredValidationEmptyTextRetries() {
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.text(""))
			.then(ScriptedClient.text("{\"name\":\"ok\"}"));
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, NamedRecord.class);
		AdvisorChain chain = new AdvisorChain(List.of(new StructuredOutputValidationAdvisor(2)),
			c -> client.chat(c.rebuildRequest()));
		ChatResponse resp = chain.execute(ctx);
		assertEquals("{\"name\":\"ok\"}", resp.firstText());
	}

	/** 结构化输出校验：终端返回 null 时透传。 */
	@Test
	public void structuredValidationNullResponsePassesThrough() {
		StructuredOutputValidationAdvisor advisor = new StructuredOutputValidationAdvisor();
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, NamedRecord.class);
		AdvisorChain chain = new AdvisorChain(List.of(advisor), c -> null);
		assertNull(chain.execute(ctx));
	}

	/** 结构化输出校验：输出是 JSON 数组而非对象时重试。 */
	@Test
	public void structuredValidationNonJsonObjectRetries() {
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.text("[1,2,3]"))
			.then(ScriptedClient.text("{\"name\":\"ok\"}"));
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, NamedRecord.class);
		AdvisorChain chain = new AdvisorChain(List.of(new StructuredOutputValidationAdvisor(2)),
			c -> client.chat(c.rebuildRequest()));
		ChatResponse resp = chain.execute(ctx);
		assertEquals("{\"name\":\"ok\"}", resp.firstText());
		assertEquals(2, client.callCount());
	}

	/** 结构化输出目标 record。 */
	public record NamedRecord(String name) {
	}
}
