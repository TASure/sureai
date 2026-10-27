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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.sure.ai.evals.dataset.EvalCase;

/**
 * 包含指标：实际输出须包含期望子串。
 *
 * <p>默认把整条 {@code expected} 当作一个子串；设置 {@code delimiter} 后按分隔符把
 * expected 切分为多个子串。{@code allRequired}（默认 false）控制：true 时全部子串都命中
 * 才通过，false 时任一命中即通过。{@code ignoreCase}（默认 false）控制大小写敏感。
 * 期望为 null 时返回 {@link MetricResult#notApplicable(String)}。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class ContainsMetric implements EvalMetric {

	private final boolean ignoreCase;
	private final boolean allRequired;
	private final String delimiter;

	private ContainsMetric(Builder b) {
		this.ignoreCase = b.ignoreCase;
		this.allRequired = b.allRequired;
		this.delimiter = b.delimiter;
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
		return "contains";
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		String expected = evalCase.expected();
		if (expected == null) {
			return MetricResult.notApplicable("缺期望输出");
		}
		List<String> needles = split(expected);
		if (needles.isEmpty()) {
			return MetricResult.notApplicable("期望子串为空");
		}
		String actual = outcome.actualText();
		String haystack = this.ignoreCase ? actual.toLowerCase(Locale.ROOT) : actual;
		int hits = 0;
		List<String> missing = new ArrayList<>();
		for (String needle : needles) {
			String target = this.ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
			if (haystack.contains(target)) {
				hits++;
			} else {
				missing.add(needle);
			}
		}
		boolean passed = this.allRequired ? missing.isEmpty() : hits > 0;
		if (passed) {
			return MetricResult.pass("命中 " + hits + "/" + needles.size() + " 个子串");
		}
		return MetricResult.fail("未命中子串: " + missing);
	}

	private List<String> split(String expected) {
		if (this.delimiter == null || this.delimiter.isEmpty()) {
			return List.of(expected);
		}
		List<String> result = new ArrayList<>();
		for (String part : expected.split(Pattern.quote(this.delimiter))) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private boolean ignoreCase;
		private boolean allRequired;
		private String delimiter;

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
		 * 是否要求全部子串命中（默认 false=任一命中）。
		 *
		 * @param allRequired 开关
		 * @return this
		 */
		public Builder allRequired(boolean allRequired) {
			this.allRequired = allRequired;
			return this;
		}

		/**
		 * 设置期望子串分隔符（默认 null=整条期望作为一个子串）。
		 *
		 * @param delimiter 分隔符
		 * @return this
		 */
		public Builder delimiter(String delimiter) {
			this.delimiter = delimiter;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public ContainsMetric build() {
			return new ContainsMetric(this);
		}
	}
}
