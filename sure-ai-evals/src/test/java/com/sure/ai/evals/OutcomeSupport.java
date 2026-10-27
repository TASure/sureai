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

import java.util.List;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.metric.EvalContext;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;

/**
 * 指标测试公共构造工具。
 */
final class OutcomeSupport {

	private OutcomeSupport() {
	}

	static EvalCase caseOf(String expected) {
		return EvalCase.of("t", "问题", expected);
	}

	static EvalOutcome successOutcome(String text) {
		return successOutcome(text, TokenUsage.of(2, 3, 5), 0L);
	}

	static EvalOutcome successOutcome(String text, TokenUsage usage, long nanos) {
		EvalCase c = caseOf(null);
		ChatResponse response = ChatResponse.of("r", "m",
			List.of(Choice.of(0, ChatMessage.assistant(text), "stop")), usage, null);
		return EvalOutcome.success(c, response, nanos);
	}

	static EvalContext context() {
		return EvalContext.create("test-model");
	}

	static com.sure.ai.evals.metric.EvalMetric passMetric() {
		return new com.sure.ai.evals.metric.EvalMetric() {
			@Override
			public String name() {
				return "pass_metric";
			}

			@Override
			public MetricResult evaluate(com.sure.ai.evals.dataset.EvalCase c,
					com.sure.ai.evals.metric.EvalOutcome o,
					com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.pass("ok");
			}
		};
	}
}
