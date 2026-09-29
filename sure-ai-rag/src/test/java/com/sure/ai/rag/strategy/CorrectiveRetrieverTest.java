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
package com.sure.ai.rag.strategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.Retriever;

/**
 * CRAG 纠正式检索器测试。
 */
public class CorrectiveRetrieverTest {

	/** 记录 retrieve 调用次数与 topK 的底层检索器。 */
	private static final class CountingRetriever implements Retriever {

		private final List<Document> candidates;
		private final List<Integer> calledTopKs = new ArrayList<>();
		private int callCount = 0;

		CountingRetriever(List<Document> candidates) {
			this.candidates = candidates;
		}

		@Override
		public List<Document> retrieve(String query, int topK) {
			this.callCount++;
			this.calledTopKs.add(topK);
			return new ArrayList<>(this.candidates);
		}
	}

	/** 按文档关键词返回「相关/不相关」判定的评估客户端。 */
	private static final class ScriptedEvalClient implements AiClient {

		private final boolean throwOnEval;

		ScriptedEvalClient(boolean throwOnEval) {
			this.throwOnEval = throwOnEval;
		}

		@Override
		public String name() {
			return "eval";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			if (this.throwOnEval) {
				throw new RuntimeException("eval down");
			}
			String content = request.messages().get(0).content();
			String answer = content != null && content.contains("相关DOC")
					? "相关。该文档直接回答了问题。"
					: "不相关。该文档与查询主题无关。";
			return ChatResponse.of("r", "m",
					List.of(Choice.of(0, ChatMessage.assistant(answer), "stop")),
					TokenUsage.of(1, 1, 2), null);
		}

		@Override
		public void chatStream(ChatRequest request,
				java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
			// no-op
		}

		@Override
		public void close() {
			// no-op
		}
	}

	private static Document doc(String id, String text) {
		return Document.of(id, text);
	}

	@Test
	public void testFiltersToRelevantOnly() {
		CountingRetriever bottom = new CountingRetriever(new ArrayList<>(Arrays.asList(
				doc("rel", "相关DOC正文"), doc("irrel", "无关DOC正文"))));
		CorrectiveRetriever retriever = CorrectiveRetriever.builder()
				.retriever(bottom).chatClient(new ScriptedEvalClient(false)).model("m")
				.build();

		List<Document> result = retriever.retrieve("问题", 5);
		assertEquals(1, result.size());
		assertEquals("rel", result.get(0).id());
	}

	@Test
	public void testAllIrrelevantUsesWebProvider() {
		// 两个候选都被判为不相关
		CountingRetriever bottom = new CountingRetriever(new ArrayList<>(Arrays.asList(
				doc("a", "无关DOC正文A"), doc("b", "无关DOC正文B"))));
		WebSearchProvider web = (query, k) -> List.of(doc("web1", "网络结果一"), doc("web2", "网络结果二"));

		CorrectiveRetriever retriever = CorrectiveRetriever.builder()
				.retriever(bottom).chatClient(new ScriptedEvalClient(false)).model("m")
				.webSearchProvider(web)
				.build();

		List<Document> result = retriever.retrieve("问题", 5);
		assertEquals(2, result.size());
		assertEquals("web1", result.get(0).id());
		assertEquals("web2", result.get(1).id());
	}

	@Test
	public void testAllIrrelevantWithoutProviderDegrades() {
		CountingRetriever bottom = new CountingRetriever(new ArrayList<>(Arrays.asList(
				doc("a", "无关DOC正文A"), doc("b", "无关DOC正文B"))));

		CorrectiveRetriever retriever = CorrectiveRetriever.builder()
				.retriever(bottom).chatClient(new ScriptedEvalClient(false)).model("m")
				.build();

		List<Document> result = retriever.retrieve("问题", 2);
		// 第二次调用为降级重检，topK 放大 3 倍
		assertEquals(2, bottom.callCount);
		assertEquals(Integer.valueOf(2), bottom.calledTopKs.get(0));
		assertEquals(Integer.valueOf(2 * CorrectiveRetriever.DEGRADED_RECALL_MULTIPLIER),
				bottom.calledTopKs.get(1));
		// 返回的文档标注了降级标记
		assertEquals(2, result.size());
		for (Document d : result) {
			assertEquals("true", d.metadata().get(CorrectiveRetriever.META_DEGRADED));
		}
	}

	@Test
	public void testEvalExceptionKeepsAllCandidates() {
		CountingRetriever bottom = new CountingRetriever(new ArrayList<>(Arrays.asList(
				doc("a", "正文A"), doc("b", "正文B"))));
		CorrectiveRetriever retriever = CorrectiveRetriever.builder()
				.retriever(bottom).chatClient(new ScriptedEvalClient(true)).model("m")
				.build();

		List<Document> result = retriever.retrieve("问题", 5);
		// 评估异常 → 保守保留全部候选，且只检索一次
		assertEquals(2, result.size());
		assertEquals(1, bottom.callCount);
		assertNotNull(result);
	}
}
