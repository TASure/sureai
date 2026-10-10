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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;

/**
 * 评估模块补覆盖测试：{@link AnswerRelevancyMetric} 余弦相似度边界与 embedding 口径
 * NaN 回退、{@link LlmJudge} 静态解析容错、{@link RagEvaluator} 不适用指标聚合、
 * {@link EvaluationResult} 摘要、{@link EvaluationAssertions} 失败路径、
 * {@link RagTrace} 四参工厂、各指标 Builder 链式 setter，以及工具类私有构造器守卫，零网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class EvaluationCoverageExtraTest {

	/** 空上下文。 */
	private static RagTrace emptyCtxTrace() {
		return RagTrace.builder().question("q").answer("a").contexts(List.<Document>of()).build();
	}

	/** 带上下文轨迹。 */
	private static RagTrace trace() {
		return RagTrace.builder().question("q").answer("a")
				.contexts(List.of(new Document("d1", "ctx", Map.of()))).build();
	}

	/** 永远返回指定向量的 mock 向量化提供者。 */
	private static EmbeddingProvider fixedProvider(float[] orig, List<float[]> all) {
		return new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				return orig;
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				return all;
			}
		};
	}

	// ===================== AnswerRelevancyMetric =====================

	/** cosineSimilarity：入参 null/空数组/长度不一致 → NaN。 */
	@Test
	public void testCosineSimilarityDegenerate() {
		assertTrue(Double.isNaN(AnswerRelevancyMetric.cosineSimilarity(null, new float[] { 1f })));
		assertTrue(Double.isNaN(AnswerRelevancyMetric.cosineSimilarity(new float[] {}, new float[] {})));
		assertTrue(Double.isNaN(AnswerRelevancyMetric.cosineSimilarity(new float[] { 1f }, new float[] { 1f, 2f })));
	}

	/** cosineSimilarity：零向量 → NaN。 */
	@Test
	public void testCosineSimilarityZeroVector() {
		assertTrue(Double.isNaN(AnswerRelevancyMetric.cosineSimilarity(new float[] { 0f, 0f }, new float[] { 1f, 1f })));
	}

	/** embedding 口径：反推问题为空 → NaN 后回退 LLM 直接评分。 */
	@Test
	public void testRelevancyEmbeddingReverseEmptyFallsBack() {
		ScriptedChatClient client = new ScriptedChatClient("", "0.8");
		AnswerRelevancyMetric metric = AnswerRelevancyMetric.builder().chatClient(client).model("m")
				.embeddingProvider(fixedProvider(new float[] { 0.1f, 0.2f }, List.of())).build();
		assertEquals(0.8, metric.evaluate(trace()), 0.001);
	}

	/** embedding 口径：原问题向量为 null → NaN 后回退 LLM 评分。 */
	@Test
	public void testRelevancyEmbeddingOriginalNullFallsBack() {
		ScriptedChatClient client = new ScriptedChatClient("q1", "0.3");
		AnswerRelevancyMetric metric = AnswerRelevancyMetric.builder().chatClient(client).model("m")
				.embeddingProvider(fixedProvider(null, List.of(new float[] { 0.1f }))).build();
		assertEquals(0.3, metric.evaluate(trace()), 0.001);
	}

	/** embedding 口径：embed 抛运行时异常 → NaN 后回退 LLM 评分。 */
	@Test
	public void testRelevancyEmbeddingThrowsFallsBack() {
		EmbeddingProvider throwing = new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				throw new IllegalStateException("boom");
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				throw new IllegalStateException("boom");
			}
		};
		ScriptedChatClient client = new ScriptedChatClient("q1", "0.6");
		AnswerRelevancyMetric metric = AnswerRelevancyMetric.builder().chatClient(client).model("m")
				.embeddingProvider(throwing).build();
		assertEquals(0.6, metric.evaluate(trace()), 0.001);
	}

	/** Builder maxTokens 链式 setter。 */
	@Test
	public void testRelevancyBuilderMaxTokens() {
		assertNotNull(AnswerRelevancyMetric.builder().chatClient(new ScriptedChatClient())
				.model("m").maxTokens(256).build());
	}

	// ===================== LlmJudge 静态解析 =====================

	/** parseScore01：空白文本 → 默认值。 */
	@Test
	public void testParseScoreBlank() {
		assertEquals(0.5, LlmJudge.parseScore01(null, 0.5), 0.001);
		assertEquals(0.5, LlmJudge.parseScore01("   ", 0.5), 0.001);
	}

	/** parseLineItems：去项目符号/行号、跳过空行。 */
	@Test
	public void testParseLineItemsStripsBullets() {
		List<String> items = LlmJudge.parseLineItems("- a\n\n1. b\n* c\n2、d");
		assertEquals(List.of("a", "b", "c", "d"), items);
		assertTrue(LlmJudge.parseLineItems(null).isEmpty());
	}

	/** parseYesNo：无法判定 → 默认值。 */
	@Test
	public void testParseYesNoUnknown() {
		assertFalse(LlmJudge.parseYesNo("maybe so", false));
	}

	/** model() 取值：用最小子类暴露。 */
	@Test
	public void testModelAccessor() {
		ScriptedChatClient client = new ScriptedChatClient();
		LlmJudgeAccessor judge = new LlmJudgeAccessor(client, "gpt-test", 64) {
		};
		assertEquals("gpt-test", judge.modelName());
	}

	/** 暴露 protected model() 的桥接子类。 */
	private abstract static class LlmJudgeAccessor extends LlmJudge {
		LlmJudgeAccessor(com.sure.ai.client.AiClient client, String model, int maxTokens) {
			super(client, model, maxTokens);
		}

		String modelName() {
			return model();
		}
	}

	// ===================== RagEvaluator / EvaluationResult =====================

	/** 指标返回 NaN → 归入 notApplicable；metrics() 返回持有列表。 */
	@Test
	public void testEvaluatorNotApplicable() {
		RagMetric nan = new RagMetric() {
			@Override
			public String name() {
				return "nan-metric";
			}

			@Override
			public double evaluate(RagTrace trace) {
				return Double.NaN;
			}
		};
		RagEvaluator evaluator = RagEvaluator.of(List.of(nan));
		assertEquals(1, evaluator.metrics().size());
		EvaluationResult r = evaluator.evaluate(trace());
		assertTrue(Double.isNaN(r.overall()));
		assertTrue(r.summary().contains("notApplicable=[nan-metric]"));
	}

	/** evaluateAll：某指标在所有轨迹上均 NaN → 归入 notApplicable。 */
	@Test
	public void testEvaluateAllAllNan() {
		RagMetric nan = new RagMetric() {
			@Override
			public String name() {
				return "always-nan";
			}

			@Override
			public double evaluate(RagTrace trace) {
				return Double.NaN;
			}
		};
		RagEvaluator evaluator = RagEvaluator.of(List.of(nan));
		EvaluationResult r = evaluator.evaluateAll(List.of(trace(), trace()));
		assertTrue(r.summary().contains("notApplicable=[always-nan]"));
	}

	// ===================== EvaluationAssertions =====================

	/** 指标缺失/NaN → assertMeets 抛 AssertionError。 */
	@Test
	public void testAssertMeetsMissingMetricThrows() {
		RagMetric one = new RagMetric() {
			@Override
			public String name() {
				return "good";
			}

			@Override
			public double evaluate(RagTrace trace) {
				return 0.9;
			}
		};
		RagEvaluator evaluator = RagEvaluator.of(List.of(one));
		EvaluationResult result = evaluator.evaluate(trace());
		EvaluationThreshold threshold = EvaluationThreshold.of("missing", 0.8);
		assertThrows(AssertionError.class, () -> EvaluationAssertions.assertMeets(result, threshold));
	}

	// ===================== RagTrace 工厂 =====================

	/** 四参 of 工厂带参考答案。 */
	@Test
	public void testTraceOfWithReference() {
		RagTrace t = RagTrace.of("q", "a", List.of(new Document("d1", "ctx", Map.of())), "ref-answer");
		assertEquals("ref-answer", t.referenceAnswer());
	}

	// ===================== ContextPrecision / Recall =====================

	/** ContextPrecision：空上下文 → 0.0。 */
	@Test
	public void testPrecisionEmptyContexts() {
		ScriptedChatClient client = new ScriptedChatClient();
		ContextPrecisionMetric metric = ContextPrecisionMetric.builder().chatClient(client).model("m").build();
		assertEquals(0.0, metric.evaluate(emptyCtxTrace()), 0.001);
	}

	/** ContextPrecision：模型全判不相关 → 0.0；Builder maxTokens。 */
	@Test
	public void testPrecisionNoneRelevant() {
		ScriptedChatClient client = new ScriptedChatClient("no", "no");
		ContextPrecisionMetric metric = ContextPrecisionMetric.builder().chatClient(client).model("m")
				.maxTokens(128).build();
		assertEquals(0.0, metric.evaluate(trace()), 0.001);
	}

	/** ContextRecall：无参考答案 → NaN；Builder maxTokens。 */
	@Test
	public void testRecallNoReference() {
		ScriptedChatClient client = new ScriptedChatClient();
		ContextRecallMetric metric = ContextRecallMetric.builder().chatClient(client).model("m")
				.maxTokens(128).build();
		assertTrue(Double.isNaN(metric.evaluate(trace())));
	}

	// ===================== 工具类私有构造器守卫 =====================

	/** 反射调用工具类私有构造器，断言抛 AssertionError。 */
	@Test
	public void testUtilPrivateConstructors() throws Exception {
		assertPrivateCtorThrows(TraceSerializer.class);
		assertPrivateCtorThrows(EvaluationAssertions.class);
	}

	private static void assertPrivateCtorThrows(Class<?> clazz) throws Exception {
		Constructor<?> c = clazz.getDeclaredConstructor();
		c.setAccessible(true);
		try {
			c.newInstance();
			fail(clazz.getSimpleName() + " 私有构造器应抛 AssertionError");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof AssertionError);
		}
	}
}
