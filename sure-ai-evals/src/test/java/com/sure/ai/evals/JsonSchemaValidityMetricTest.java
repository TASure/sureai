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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.evals.metric.JsonSchemaValidityMetric;
import com.sure.ai.evals.metric.MetricResult;

/**
 * {@link JsonSchemaValidityMetric} 测试。
 */
public class JsonSchemaValidityMetricTest {

	record Person(String name, int age, Gender gender) {
	}

	enum Gender {
		MALE, FEMALE
	}

	private final JsonSchemaValidityMetric metric = JsonSchemaValidityMetric.builder(Person.class).build();

	@Test
	public void testValid() {
		MetricResult r = metric.evaluate(caseOf(null),
			successOutcome("{\"name\":\"tom\",\"age\":18,\"gender\":\"MALE\"}"), context());
		assertEquals(1.0, r.score(), 0.0);
		assertEquals("json_schema", metric.name());
	}

	@Test
	public void testMissingRequired() {
		MetricResult r = metric.evaluate(caseOf(null),
			successOutcome("{\"name\":\"tom\"}"), context());
		assertTrue(r.score() < 1.0);
		assertTrue(r.reason().contains("age"));
	}

	@Test
	public void testWrongType() {
		MetricResult r = metric.evaluate(caseOf(null),
			successOutcome("{\"age\":\"notANumber\"}"), context());
		assertEquals(0.0, r.score(), 0.0);
	}

	@Test
	public void testEnumMismatch() {
		MetricResult r = metric.evaluate(caseOf(null),
			successOutcome("{\"age\":1,\"gender\":\"UNKNOWN\"}"), context());
		assertTrue(r.score() < 1.0);
	}

	@Test
	public void testNotJson() {
		MetricResult r = metric.evaluate(caseOf(null), successOutcome("这不是JSON"), context());
		assertEquals(0.0, r.score(), 0.0);
	}

	@Test
	public void testRootNotObject() {
		MetricResult r = metric.evaluate(caseOf(null), successOutcome("[1,2,3]"), context());
		assertEquals(0.0, r.score(), 0.0);
	}

	@Test
	public void testEmptyOutput() {
		MetricResult r = metric.evaluate(caseOf(null), successOutcome(""), context());
		assertEquals(0.0, r.score(), 0.0);
	}

	@Test
	public void testEmptySchemaPasses() {
		JsonSchemaValidityMetric m = JsonSchemaValidityMetric.builder(Void.class).build();
		MetricResult r = m.evaluate(caseOf(null), successOutcome("{}"), context());
		assertEquals(1.0, r.score(), 0.0);
	}
}
