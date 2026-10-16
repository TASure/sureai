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

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.sure.ai.evals.dataset.EvalCase;

/**
 * 正则匹配指标：把期望输出当作正则表达式，在实际输出上匹配。
 *
 * <p>匹配方式可配：{@link Mode#FIND}（默认，{@code find()} 部分匹配）或
 * {@link Mode#MATCHES}（{@code matches()} 全串匹配）。期望为 null 返回
 * {@link MetricResult#notApplicable(String)}；期望正则非法时判 0 分并在理由中给出
 * 编译错误（确定性失败，不向上抛出）。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class RegexMetric implements EvalMetric {

	/** 匹配模式。 */
	public enum Mode {
		/** 部分匹配（find）。 */
		FIND,
		/** 全串匹配（matches）。 */
		MATCHES
	}

	private final Mode mode;

	private RegexMetric(Builder b) {
		this.mode = b.mode;
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
		return "regex";
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		String regex = evalCase.expected();
		if (regex == null) {
			return MetricResult.notApplicable("缺期望正则");
		}
		Pattern pattern;
		try {
			pattern = Pattern.compile(regex);
		} catch (PatternSyntaxException e) {
			return MetricResult.fail("期望正则非法: " + e.getDescription());
		}
		boolean hit = this.mode == Mode.MATCHES
			? pattern.matcher(outcome.actualText()).matches()
			: pattern.matcher(outcome.actualText()).find();
		return hit ? MetricResult.pass("正则匹配成功")
			: MetricResult.fail("正则未匹配: " + snippet(regex));
	}

	private static String snippet(String text) {
		String t = text.strip();
		return t.length() > 60 ? t.substring(0, 60) + "…" : t;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private Mode mode = Mode.FIND;

		private Builder() {
		}

		/**
		 * 设置匹配模式（默认 FIND）。
		 *
		 * @param mode 模式
		 * @return this
		 */
		public Builder mode(Mode mode) {
			this.mode = mode;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public RegexMetric build() {
			return new RegexMetric(this);
		}
	}
}
