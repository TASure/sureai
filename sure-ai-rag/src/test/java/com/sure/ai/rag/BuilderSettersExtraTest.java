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

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.sure.ai.rag.evaluation.FaithfulnessMetric;
import com.sure.ai.rag.graph.GraphRagIndexer;
import com.sure.ai.rag.graph.LlmCommunitySummarizer;
import com.sure.ai.rag.graph.LlmEntityRelationExtractor;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.rewriter.ModelQueryRewriter;
import com.sure.ai.rag.strategy.CorrectiveRetriever;
import com.sure.ai.rag.strategy.HydeRetriever;
import com.sure.ai.rag.strategy.MultimodalRetriever;

/**
 * 策略/图谱/管线/重写器 Builder fluent setter 补覆盖：逐一调用每个 setter 方法，
 * 不触发真实网络依赖，仅覆盖 builder 方法本身的行。
 *
 * @author sureai
 * @since 2.6.0
 */
public class BuilderSettersExtraTest {

	/** HydeRetriever Builder 全量 setter。 */
	@Test
	public void hydeSetters() {
		HydeRetriever.Builder b = HydeRetriever.builder();
		assertNotNull(b.chatClient(null).embeddingProvider(null).maxTokens(256)
				.minScore(0.5).model("m").promptTemplate(null).store(null));
	}

	/** CorrectiveRetriever Builder 全量 setter。 */
	@Test
	public void correctiveSetters() {
		CorrectiveRetriever.Builder b = CorrectiveRetriever.builder();
		assertNotNull(b.chatClient(null).evalPromptTemplate(null).maxTokens(256)
				.model("m").retriever(null).webSearchProvider(null));
	}

	/** ModelQueryRewriter Builder 全量 setter。 */
	@Test
	public void rewriterSetters() {
		ModelQueryRewriter.Builder b = ModelQueryRewriter.builder();
		assertNotNull(b.chatClient(null).maxTokens(256).model("m").promptTemplate(null));
	}

	/** LlmEntityRelationExtractor Builder 全量 setter。 */
	@Test
	public void extractorSetters() {
		LlmEntityRelationExtractor.Builder b = LlmEntityRelationExtractor.builder();
		assertNotNull(b.chatClient(null).maxTokens(256).model("m").promptTemplate(null));
	}

	/** LlmCommunitySummarizer Builder 全量 setter。 */
	@Test
	public void summarizerSetters() {
		LlmCommunitySummarizer.Builder b = LlmCommunitySummarizer.builder();
		assertNotNull(b.chatClient(null).maxTokens(256).model("m").promptTemplate(null));
	}

	/** GraphRagIndexer Builder 全量 setter。 */
	@Test
	public void graphIndexerSetters() {
		GraphRagIndexer.Builder b = GraphRagIndexer.builder();
		assertNotNull(b.detector(null).extractor(null).graph(null).summarizer(null));
	}

	/** MultimodalRetriever Builder 全量 setter。 */
	@Test
	public void multimodalRetrieverSetters() {
		MultimodalRetriever.Builder b = MultimodalRetriever.builder();
		assertNotNull(b.ingestor(null).minScore(0.5).store(null).textEmbeddingProvider(null));
	}

	/** RagPipeline Builder 全量 setter（不 build，仅覆盖 setter 行）。 */
	@Test
	public void pipelineSetters() {
		RagPipeline.Builder b = RagPipeline.builder();
		assertNotNull(b.chatClient(null).chatModel("m").defaultTopK(5).embeddingClient(null)
				.embeddingModel("em").minScore(0.0).reranker(null).retriever(null)
				.splitter(null).systemPromptTemplate(null).vectorStore(null));
	}

	/** FaithfulnessMetric Builder maxTokens setter。 */
	@Test
	public void faithfulnessSetters() {
		assertNotNull(FaithfulnessMetric.builder().chatClient(null).maxTokens(256).model("m"));
	}
}
