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

package com.sure.ai.evals.metric;

import java.util.Locale;

import com.sure.ai.evals.dataset.EvalCase;

/**
 * 精确匹配指标：归一化后比较实际输出与期望输出是否完全一致。
 *
 * <p>归一化策略均可配：{@code normalizeWhitespace}（默认 true）——去除首尾空白并把内部
 * 连续空白折叠为单个空格；{@code ignoreCase}（默认 false）——忽略大小写。
 * 期望（{@code expected}）为 null 时返回 {@link MetricResult#notApplicable(String)}。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class ExactMatchMetric implements EvalMetric {

	private final boolean ignoreCase;
	private final boolean normalizeWhitespace;

	private ExactMatchMetric(Builder b) {
		this.ignoreCase = b.ignoreCase;
		this.normalizeWhitespace = b.normalizeWhitespace;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String name() {
		return "exact_match";
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		String expected = evalCase.expected();
		if (expected == null) {
			return MetricResult.notApplicable("缺期望输出");
		}
		String actual = outcome.actualText();
		if (normalize(actual).equals(normalize(expected))) {
			return MetricResult.pass("精确匹配");
		}
		return MetricResult.fail("不匹配：实际=[" + snippet(actual) + "] 期望=[" + snippet(expected) + "]");
	}

	private String normalize(String text) {
		String t = text;
		if (this.normalizeWhitespace) {
			t = t.strip().replaceAll("\\s+", " ");
		}
		if (this.ignoreCase) {
			t = t.toLowerCase(Locale.ROOT);
		}
		return t;
	}

	private static String snippet(String text) {
		String t = text.strip();
		return t.length() > 80 ? t.substring(0, 80) + "…" : t;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private boolean ignoreCase;
		private boolean normalizeWhitespace = true;

		private Builder() {
		}

		/**
		 * 是否忽略大小写。
		 *
		 * @param ignoreCase 开关
		 * @return this
		 */
		public Builder ignoreCase(boolean ignoreCase) {
			this.ignoreCase = ignoreCase;
			return this;
		}

		/**
		 * 是否归一化空白（默认 true）。
		 *
		 * @param normalizeWhitespace 开关
		 * @return this
		 */
		public Builder normalizeWhitespace(boolean normalizeWhitespace) {
			this.normalizeWhitespace = normalizeWhitespace;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public ExactMatchMetric build() {
			return new ExactMatchMetric(this);
		}
	}
}
