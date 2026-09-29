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

package com.sure.ai.rag.evaluation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;

/**
 * RAG 评估包测试：零真实网络，全部使用脚本化客户端。
 */
public class RagEvaluationTest {

	private static final double DELTA = 0.001;

	private Document doc(String id, String text) {
		return Document.of(id, text);
	}

	private RagTrace sampleTrace() {
		return RagTrace.builder()
				.question("什么是 RAG？")
				.answer("RAG 是检索增强生成。")
				.contexts(List.of(doc("d1", "RAG 通过检索外部知识增强大模型生成。"),
						doc("d2", "向量数据库用于存储嵌入。")))
				.referenceAnswer("RAG 结合检索与生成。")
				.metadata("model", "test-model")
				.build();
	}

	@Test
	public void testTraceBuildAndFields() {
		RagTrace trace = sampleTrace();
		assertEquals("什么是 RAG？", trace.question());
		assertEquals("RAG 是检索增强生成。", trace.answer());
		assertEquals(2, trace.contexts().size());
		assertEquals("RAG 结合检索与生成。", trace.referenceAnswer());
		assertEquals("test-model", trace.metadata().get("model"));
		assertFalse(trace.traceId().isBlank());
		// 上下文不可变视图
		try {
			trace.contexts().add(doc("x", "x"));
			fail("contexts 应不可变");
		} catch (UnsupportedOperationException expected) {
			// pass
		}
	}

	@Test
	public void testTraceSerializerRoundTrip() {
		RagTrace trace = sampleTrace();
		String json = TraceSerializer.toJson(trace);
		RagTrace restored = TraceSerializer.fromJson(json);
		assertEquals(trace.traceId(), restored.traceId());
		assertEquals(trace.question(), restored.question());
		assertEquals(trace.answer(), restored.answer());
		assertEquals(trace.referenceAnswer(), restored.referenceAnswer());
		assertEquals(trace.contexts().size(), restored.contexts().size());
		assertEquals(trace.contexts().get(0).id(), restored.contexts().get(0).id());
		assertEquals(trace.contexts().get(0).text(), restored.contexts().get(0).text());
		assertEquals("test-model", restored.metadata().get("model"));
	}

	@Test
	public void testTraceSerializerListRoundTrip() {
		List<RagTrace> traces = List.of(sampleTrace(), sampleTrace());
		String json = TraceSerializer.toJsonList(traces);
		List<RagTrace> restored = TraceSerializer.fromJsonList(json);
		assertEquals(2, restored.size());
		assertEquals(traces.get(0).question(), restored.get(0).question());
	}

	@Test
	public void testInMemoryTraceStoreSaveListRead() {
		InMemoryTraceStore store = InMemoryTraceStore.create();
		RagTrace t1 = sampleTrace();
		RagTrace t2 = RagTrace.of("q2", "a2", List.of());
		store.save(t1);
		store.save(t2);
		assertEquals(2, store.size());
		assertEquals(2, store.all().size());
		assertEquals(t1.traceId(), store.findById(t1.traceId()).traceId());
		assertEquals(t2.answer(), store.findById(t2.traceId()).answer());
		assertEquals(null, store.findById("not-exists"));
	}

	@Test
	public void testLlmJudgeParseYesNo() {
		assertTrue(LlmJudge.parseYesNo("YES", false));
		assertTrue(LlmJudge.parseYesNo("是的，支持", false));
		assertFalse(LlmJudge.parseYesNo("NO", true));
		assertFalse(LlmJudge.parseYesNo("不支持", true));
		assertFalse(LlmJudge.parseYesNo("不相关", true));
		// 无法判定回退默认
		assertTrue(LlmJudge.parseYesNo("maybe", true));
		assertFalse(LlmJudge.parseYesNo("", false));
	}

	@Test
	public void testLlmJudgeParseScore() {
		assertEquals(0.8, LlmJudge.parseScore01("0.8", 0.5), DELTA);
		assertEquals(0.8, LlmJudge.parseScore01("80", 0.5), DELTA);
		assertEquals(0.5, LlmJudge.parseScore01("abc", 0.5), DELTA);
		assertEquals(1.0, LlmJudge.parseScore01("1.5", 0.5), DELTA);
		assertEquals(0.0, LlmJudge.parseScore01("-0.2", 0.5), DELTA);
	}

	@Test
	public void testLlmJudgeExceptionDoesNotPropagate() {
		AiClient throwing = new ThrowingClient();
		FaithfulnessMetric metric = FaithfulnessMetric.builder()
				.chatClient(throwing).model("m").build();
		// 抛异常时 ask 返回 null → 无 claim → 1.0，不抛
		double score = metric.evaluate(sampleTrace());
		assertEquals(1.0, score, DELTA);
	}

	@Test
	public void testFaithfulnessThreeClaimsTwoSupported() {
		ScriptedChatClient client = new ScriptedChatClient(
				"claim one\nclaim two\nclaim three",
				"YES", "YES", "NO");
		FaithfulnessMetric metric = FaithfulnessMetric.builder()
				.chatClient(client).model("m").build();
		double score = metric.evaluate(sampleTrace());
		assertEquals(2.0 / 3.0, score, 0.01);
	}

	@Test
	public void testFaithfulnessAllSupported() {
		ScriptedChatClient client = new ScriptedChatClient("c1\nc2", "YES", "YES");
		FaithfulnessMetric metric = FaithfulnessMetric.builder()
				.chatClient(client).model("m").build();
		assertEquals(1.0, metric.evaluate(sampleTrace()), DELTA);
	}

	@Test
	public void testFaithfulnessNoClaimsReturnsOne() {
		ScriptedChatClient client = new ScriptedChatClient("");
		FaithfulnessMetric metric = FaithfulnessMetric.builder()
				.chatClient(client).model("m").build();
		assertEquals(1.0, metric.evaluate(sampleTrace()), DELTA);
	}

	@Test
	public void testContextPrecisionOrderSensitive() {
		// 相关文档排前：[YES, NO] → precision@1=1.0 → 1.0
		ScriptedChatClient clientFront = new ScriptedChatClient("YES", "NO");
		ContextPrecisionMetric front = ContextPrecisionMetric.builder()
				.chatClient(clientFront).model("m").build();
		double scoreFront = front.evaluate(sampleTrace());
		assertEquals(1.0, scoreFront, DELTA);

		// 相关文档排后：[NO, YES] → precision@2=0.5 → 0.5
		ScriptedChatClient clientBack = new ScriptedChatClient("NO", "YES");
		ContextPrecisionMetric back = ContextPrecisionMetric.builder()
				.chatClient(clientBack).model("m").build();
		double scoreBack = back.evaluate(sampleTrace());
		assertEquals(0.5, scoreBack, DELTA);

		assertTrue("相关排前应高于排后", scoreFront > scoreBack);
	}

	@Test
	public void testContextRecallTwoOfThree() {
		ScriptedChatClient client = new ScriptedChatClient(
				"point1\npoint2\npoint3", "YES", "YES", "NO");
		ContextRecallMetric metric = ContextRecallMetric.builder()
				.chatClient(client).model("m").build();
		assertEquals(2.0 / 3.0, metric.evaluate(sampleTrace()), 0.01);
	}

	@Test
	public void testContextRecallNoReferenceIsNaN() {
		RagTrace noRef = RagTrace.of("q", "a", List.of(doc("d1", "ctx")));
		ContextRecallMetric metric = ContextRecallMetric.builder()
				.chatClient(new ScriptedChatClient()).model("m").build();
		assertTrue(Double.isNaN(metric.evaluate(noRef)));
	}

	@Test
	public void testAnswerRelevancyLlmDirectScore() {
		ScriptedChatClient client = new ScriptedChatClient("0.8");
		AnswerRelevancyMetric metric = AnswerRelevancyMetric.builder()
				.chatClient(client).model("m").build();
		assertEquals(0.8, metric.evaluate(sampleTrace()), DELTA);
	}

	@Test
	public void testAnswerRelevancyEmbeddingPath() {
		ScriptedChatClient client = new ScriptedChatClient("反推问题一\n反推问题二");
		AnswerRelevancyMetric metric = AnswerRelevancyMetric.builder()
				.chatClient(client).model("m")
				.embeddingProvider(new FixedVectorProvider()).build();
		// 固定单位向量 → 余弦恒为 1.0
		assertEquals(1.0, metric.evaluate(sampleTrace()), DELTA);
		// embedding 路径只发一次反推问题请求（不再走 LLM 直接评分）
		assertEquals(1, client.prompts().size());
	}

	@Test
	public void testEvaluatorFourMetricsAndOverall() {
		ScriptedChatClient client = new ScriptedChatClient(
				"c1\nc2", "YES", "YES",          // faithfulness = 1.0
				"YES", "NO",                     // context precision = 1.0
				"p1\np2", "YES", "YES",          // context recall = 1.0
				"0.9");                          // answer relevancy = 0.9
		RagEvaluator evaluator = RagEvaluator.llmDefault(client, "m");
		EvaluationResult result = evaluator.evaluate(sampleTrace());
		assertEquals(4, result.scores().size());
		assertTrue(result.scores().containsKey("faithfulness"));
		assertTrue(result.scores().containsKey("context_precision"));
		assertTrue(result.scores().containsKey("context_recall"));
		assertTrue(result.scores().containsKey("answer_relevancy"));
		assertEquals(0.975, result.overall(), 0.01);
	}

	@Test
	public void testEvaluatorBatchMean() {
		ScriptedChatClient client = new ScriptedChatClient(
				"c1\nc2", "YES", "NO",   // trace1 faithfulness = 0.5
				"c1", "YES");            // trace2 faithfulness = 1.0
		FaithfulnessMetric faith = FaithfulnessMetric.builder()
				.chatClient(client).model("m").build();
		RagEvaluator evaluator = RagEvaluator.of(List.of(faith));
		RagTrace t1 = RagTrace.of("q1", "a1", List.of(doc("d1", "x")));
		RagTrace t2 = RagTrace.of("q2", "a2", List.of(doc("d1", "x")));
		EvaluationResult result = evaluator.evaluateAll(List.of(t1, t2));
		assertEquals(0.75, result.overall(), DELTA);
		assertEquals(0.75, result.score("faithfulness"), DELTA);
	}

	@Test
	public void testThresholdPasses() {
		EvaluationResult result = EvaluationResult.of(
				Map.of("faithfulness", 0.9), List.of(), 0.9);
		EvaluationThreshold threshold = EvaluationThreshold.of("faithfulness", 0.8);
		EvaluationAssertions.assertMeets(result, threshold);
	}

	@Test
	public void testThresholdFailsWithMessage() {
		EvaluationResult result = EvaluationResult.of(
				Map.of("faithfulness", 0.5), List.of(), 0.5);
		EvaluationThreshold threshold = EvaluationThreshold.of("faithfulness", 0.8);
		try {
			EvaluationAssertions.assertMeets(result, threshold);
			fail("应抛 AssertionError");
		} catch (AssertionError e) {
			String msg = e.getMessage();
			assertTrue("消息应含指标名: " + msg, msg.contains("faithfulness"));
			assertTrue("消息应含实际值: " + msg, msg.contains("0.500"));
			assertTrue("消息应含期望值: " + msg, msg.contains("0.800"));
		}
	}

	@Test
	public void testTraceReplayFromJson() {
		RagTrace t1 = RagTrace.of("q1", "a1", List.of(doc("d1", "x")));
		RagTrace t2 = RagTrace.of("q2", "a2", List.of(doc("d1", "x")));
		String json = TraceSerializer.toJsonList(List.of(t1, t2));

		ScriptedChatClient client = new ScriptedChatClient(
				"c1\nc2", "YES", "NO",   // t1 faithfulness = 0.5
				"c1", "YES");            // t2 faithfulness = 1.0
		FaithfulnessMetric faith = FaithfulnessMetric.builder()
				.chatClient(client).model("m").build();
		RagEvaluator evaluator = RagEvaluator.of(List.of(faith));
		TraceReplay replay = new TraceReplay(evaluator);

		List<EvaluationResult> results = replay.replayFromJson(json);
		assertEquals(2, results.size());
		assertEquals(0.5, results.get(0).score("faithfulness"), DELTA);
		assertEquals(1.0, results.get(1).score("faithfulness"), DELTA);

		// 汇总回放使用全新脚本客户端（脚本队列一次性消费）
		ScriptedChatClient client2 = new ScriptedChatClient(
				"c1\nc2", "YES", "NO", "c1", "YES");
		FaithfulnessMetric faith2 = FaithfulnessMetric.builder()
				.chatClient(client2).model("m").build();
		RagEvaluator evaluator2 = RagEvaluator.of(List.of(faith2));
		TraceReplay replay2 = new TraceReplay(evaluator2);
		EvaluationResult aggregate = replay2.replayAggregateFromJson(json);
		assertEquals(0.75, aggregate.overall(), DELTA);
	}

	/** 抛异常的对话客户端：模拟 LLM 不可用。 */
	private static final class ThrowingClient implements AiClient {
		@Override
		public String name() {
			return "throwing";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			throw new RuntimeException("llm down");
		}

		@Override
		public void chatStream(ChatRequest request,
				java.util.function.Consumer<ChatStreamChunk> consumer) {
			// no-op
		}

		@Override
		public void close() {
			// no-op
		}
	}

	/** 固定单位向量提供者：任意文本返回同一单位向量，余弦恒为 1。 */
	private static final class FixedVectorProvider implements EmbeddingProvider {
		@Override
		public float[] embed(String text) {
			return new float[] { 1.0f, 0.0f, 0.0f };
		}

		@Override
		public List<float[]> embedAll(List<String> texts) {
			return texts.stream().map(t -> new float[] { 1.0f, 0.0f, 0.0f }).toList();
		}
	}
}
