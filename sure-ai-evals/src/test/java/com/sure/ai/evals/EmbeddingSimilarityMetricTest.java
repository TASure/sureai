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
package com.sure.ai.evals;

import static com.sure.ai.evals.OutcomeSupport.caseOf;
import static com.sure.ai.evals.OutcomeSupport.context;
import static com.sure.ai.evals.OutcomeSupport.successOutcome;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.evals.metric.EmbeddingSimilarityMetric;
import com.sure.ai.evals.metric.MetricResult;

/**
 * {@link EmbeddingSimilarityMetric} 测试。
 */
public class EmbeddingSimilarityMetricTest {

	@Test
	public void testIdenticalVectors() {
		ScriptedEmbeddingClient client = new ScriptedEmbeddingClient(
			new float[] { 1f, 0f }, new float[] { 1f, 0f });
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(client, "embed").build();
		MetricResult r = m.evaluate(caseOf("ref"), successOutcome("实际"), context());
		assertEquals(1.0, r.score(), 1e-6);
		assertTrue(r.passedAt(0.8));
		assertEquals("embedding_similarity", m.name());
	}

	@Test
	public void testOrthogonalVectors() {
		ScriptedEmbeddingClient client = new ScriptedEmbeddingClient(
			new float[] { 1f, 0f }, new float[] { 0f, 1f });
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(client, "embed").build();
		assertEquals(0.0, m.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 1e-6);
	}

	@Test
	public void testNullExpectedNotApplicable() {
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(
			new ScriptedEmbeddingClient(), "embed").build();
		assertFalse(m.evaluate(caseOf(null), successOutcome("a"), context()).applicable());
	}

	@Test
	public void testEmptyActualNotApplicable() {
		ScriptedEmbeddingClient client = new ScriptedEmbeddingClient(new float[] { 1f, 0f });
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(client, "embed").build();
		assertFalse(m.evaluate(caseOf("r"), successOutcome(""), context()).applicable());
	}

	@Test
	public void testEmbeddingErrorNotApplicable() {
		ScriptedEmbeddingClient client = new ScriptedEmbeddingClient()
			.alwaysThrow(new RuntimeException("down"));
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(client, "embed").build();
		assertFalse(m.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}

	@Test
	public void testZeroVectorNotApplicable() {
		ScriptedEmbeddingClient client = new ScriptedEmbeddingClient(
			new float[] { 0f, 0f }, new float[] { 1f, 1f });
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(client, "embed").build();
		assertFalse(m.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}

	@Test
	public void testCustomThreshold() {
		ScriptedEmbeddingClient client = new ScriptedEmbeddingClient(
			new float[] { 1f, 0f }, new float[] { 1f, 1f });
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(client, "embed")
			.threshold(0.99).build();
		assertEquals(0.99, m.passThreshold(), 0.0);
		assertFalse(m.evaluate(caseOf("r"), successOutcome("a"), context()).passedAt(0.99));
	}
}
