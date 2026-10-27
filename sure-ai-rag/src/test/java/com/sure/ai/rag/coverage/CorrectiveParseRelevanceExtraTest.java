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
package com.sure.ai.rag.coverage;

import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.strategy.CorrectiveRetriever;

/**
 * CorrectiveRetriever：retriever 返回单文档 + chat 返回空 → parseRelevance false 分支。
 *
 * @author sureai
 * @since 2.6.0
 */
public class CorrectiveParseRelevanceExtraTest {

	/** 空实现 AiClient。 */
	private static final AiClient NOOP_CHAT = new AiClient() {
		@Override
		public String name() {
			return "noop";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			return null;
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
	};

	/** CorrectiveRetriever：retriever 返回单文档 → 触发 LLM 评估。 */
	@Test
	public void testCorrectiveRetrieverWithDoc() {
		Document doc = Document.of("doc1", "content", Map.of());
		Retriever singleRetriever = (q, k) -> List.of(doc);
		CorrectiveRetriever r = CorrectiveRetriever.builder()
				.retriever(singleRetriever).chatClient(NOOP_CHAT).model("test-model").build();
		List<Document> result = r.retrieve("test", 5);
		assertNotNull(result);
	}
}
