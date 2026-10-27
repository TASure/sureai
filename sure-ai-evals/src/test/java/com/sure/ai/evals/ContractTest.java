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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalContext;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.model.ChatMessage;

/**
 * 冻结契约类的补齐测试（EvalException / MetricResult / EvalContext / EvalCase / EvalDataset）。
 */
public class ContractTest {

	@Test
	public void testEvalExceptionConstructors() {
		EvalException e1 = new EvalException("msg");
		assertEquals("msg", e1.getMessage());
		RuntimeException cause = new RuntimeException("cause");
		EvalException e2 = new EvalException("msg2", cause);
		assertEquals("msg2", e2.getMessage());
		assertSame(cause, e2.getCause());
	}

	@Test
	public void testMetricResultCanonicalizer() {
		assertEquals(0.0, MetricResult.of(-1, null).score(), 0.0);
		assertEquals(1.0, MetricResult.of(2, "x").score(), 0.0);
		assertEquals("", MetricResult.of(0.5, null).reason());
		assertTrue(MetricResult.of(0.5, "r").applicable());
		assertFalse(MetricResult.notApplicable("na").applicable());
		assertTrue(MetricResult.pass("p").passedAt(1.0));
		assertFalse(MetricResult.fail("f").passedAt(1.0));
		assertFalse(MetricResult.notApplicable("na").passedAt(0.5));
	}

	@Test
	public void testEvalContextBuilderAndAttributes() {
		EvalContext ctx = EvalContext.builder()
			.model("m").put("k", "v").put("num", 1).build();
		assertEquals("m", ctx.model());
		assertEquals("v", ctx.attribute("k", "def"));
		assertEquals("def", ctx.attribute("missing", "def"));
		assertNull(ctx.attribute("missing"));
		assertEquals("v", ctx.attribute("k"));
		assertEquals("m2", EvalContext.create("m2").model());
	}

	@Test
	public void testEvalCaseBuilderAccessors() {
		EvalCase c = EvalCase.builder()
			.id("id1")
			.input(java.util.List.of(ChatMessage.user("u")))
			.expected("exp")
			.tags(java.util.List.of("t"))
			.metadata(java.util.Map.of("a", 1))
			.expectedTools(java.util.List.of("tool"))
			.build();
		assertEquals("id1", c.id());
		assertEquals("exp", c.expected());
		assertEquals(java.util.List.of("t"), c.tags());
		assertEquals(1, c.metadata().get("a"));
		assertEquals(java.util.List.of("tool"), c.expectedTools());
		assertEquals("u", c.inputText());
	}

	@Test
	public void testEvalCaseAutoIdAndEmptyInputText() {
		EvalCase c = EvalCase.builder().build();
		assertTrue(c.id().startsWith("case-"));
		assertEquals("", c.inputText());
	}

	@Test
	public void testEvalDatasetAccessors() {
		EvalDataset ds = EvalDataset.builder()
			.name("n")
			.cases(java.util.List.of(EvalCase.of("c1", "q", "a")))
			.metadata(java.util.Map.of("x", "y"))
			.build();
		assertEquals(1, ds.size());
		assertEquals("c1", ds.get(0).id());
		assertEquals("c1", ds.byId("c1").id());
		assertNull(ds.byId("nope"));
		assertEquals("y", ds.metadata().get("x"));
		assertEquals("dataset", EvalDataset.of(java.util.List.of()).name());
	}
}
