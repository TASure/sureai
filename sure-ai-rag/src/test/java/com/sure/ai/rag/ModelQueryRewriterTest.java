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

package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.rag.rewriter.ModelQueryRewriter;

/**
 * {@link ModelQueryRewriter} 单元测试：全部使用 mock AiClient，零真实网络。
 *
 * @author sureai
 * @since 1.1.0
 */
public class ModelQueryRewriterTest {

	@Test
	public void testRewriteReturnsMultipleQueries() {
		TestChatClient client = new TestChatClient("query1\nquery2\nquery3");
		ModelQueryRewriter rewriter = new ModelQueryRewriter(client, "test-model");
		List<String> out = rewriter.rewrite("原始问题", 3);
		assertEquals(3, out.size());
		assertEquals("query1", out.get(0));
		assertEquals("query3", out.get(2));
	}

	@Test
	public void testRewriteCountLimit() {
		TestChatClient client = new TestChatClient("a\nb\nc\nd\ne");
		ModelQueryRewriter rewriter = new ModelQueryRewriter(client, "m");
		List<String> out = rewriter.rewrite("q", 3);
		assertEquals(3, out.size());
		assertEquals("c", out.get(2));
	}

	@Test
	public void testRewriteEmptyLines() {
		TestChatClient client = new TestChatClient("q1\n\n   \nq2\n");
		ModelQueryRewriter rewriter = new ModelQueryRewriter(client, "m");
		List<String> out = rewriter.rewrite("q", 5);
		assertEquals(2, out.size());
		assertEquals("q1", out.get(0));
		assertEquals("q2", out.get(1));
	}

	@Test
	public void testRewriteFallbackOnError() {
		AiClient throwing = new ThrowingChatClient();
		ModelQueryRewriter rewriter = new ModelQueryRewriter(throwing, "m");
		List<String> out = rewriter.rewrite("原始查询", 3);
		assertEquals(1, out.size());
		assertEquals("原始查询", out.get(0));
	}

	@Test
	public void testRewriteBlankReplyFallback() {
		TestChatClient client = new TestChatClient("   ");
		ModelQueryRewriter rewriter = new ModelQueryRewriter(client, "m");
		List<String> out = rewriter.rewrite("q", 3);
		assertEquals(1, out.size());
		assertEquals("q", out.get(0));
	}

	@Test
	public void testPromptContainsQueryAndCount() {
		TestChatClient client = new TestChatClient("x\ny");
		ModelQueryRewriter rewriter = new ModelQueryRewriter(client, "test-model");
		rewriter.rewrite("如何投保农险", 4);
		ChatRequest req = client.lastRequest();
		assertEquals("test-model", req.model());
		ChatMessage userMsg = req.messages().get(0);
		assertEquals(com.sure.ai.model.Role.USER, userMsg.role());
		assertTrue("提示词应包含原查询", userMsg.content().contains("如何投保农险"));
		assertTrue("提示词应包含数量", userMsg.content().contains("4"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testNonPositiveCount() {
		TestChatClient client = new TestChatClient("a\nb");
		ModelQueryRewriter rewriter = new ModelQueryRewriter(client, "m");
		rewriter.rewrite("q", 0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBuilderMissingClient() {
		ModelQueryRewriter.builder().model("m").build();
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBuilderMissingModel() {
		ModelQueryRewriter.builder().chatClient(new TestChatClient("a")).build();
	}

	/** 抛异常的测试客户端。 */
	private static final class ThrowingChatClient implements AiClient {
		@Override
		public String name() {
			return "throwing";
		}

		@Override
		public com.sure.ai.model.ChatResponse chat(ChatRequest request) {
			throw new RuntimeException("network down");
		}

		@Override
		public void chatStream(ChatRequest request, java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
			// no-op
		}

		@Override
		public void close() {
			// no-op
		}
	}
}
