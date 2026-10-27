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

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.TokenUsage;

/**
 * 单条用例的实际执行结果：被测模型的响应 / 实际文本 / 耗时 / 异常。
 *
 * <p>由 {@code EvalRunner} 在执行阶段产出，随后喂给各 {@link EvalMetric}。
 * 用例执行抛异常时 {@code error} 非 null、{@code response} 为 null，{@link #succeeded()}
 * 返回 false；失败用例仍会被指标评估（多数确定性指标据此判 0 分），但不中断整批执行。</p>
 *
 * @param evalCase     对应用例
 * @param response     模型响应，执行失败时为 null
 * @param actualText   实际输出文本（已 null 安全归一为空串）
 * @param latencyNanos 端到端耗时（纳秒）
 * @param error        执行异常，成功时为 null
 * @author sureai
 * @since 2.8.0
 */
public record EvalOutcome(EvalCase evalCase, ChatResponse response, String actualText,
		long latencyNanos, Throwable error) {

	/**
	 * 紧凑构造器：实际文本 null 安全归一。
	 */
	public EvalOutcome {
		actualText = actualText == null ? "" : actualText;
	}

	/**
	 * 构造成功结果。
	 *
	 * @param evalCase     用例
	 * @param response     响应
	 * @param latencyNanos 耗时
	 * @return 结果
	 */
	public static EvalOutcome success(EvalCase evalCase, ChatResponse response,
			long latencyNanos) {
		String text = response == null ? "" : response.firstText();
		return new EvalOutcome(evalCase, response, text == null ? "" : text, latencyNanos, null);
	}

	/**
	 * 构造失败结果。
	 *
	 * @param evalCase     用例
	 * @param error        异常
	 * @param latencyNanos 耗时
	 * @return 结果
	 */
	public static EvalOutcome failure(EvalCase evalCase, Throwable error, long latencyNanos) {
		return new EvalOutcome(evalCase, null, "", latencyNanos, error);
	}

	/**
	 * 是否执行成功。
	 *
	 * @return true 表示无异常
	 */
	public boolean succeeded() {
		return this.error == null;
	}

	/**
	 * 取响应中的 token 用量。
	 *
	 * @return 用量，缺失时返回 null
	 */
	public TokenUsage usage() {
		if (this.response == null) {
			return null;
		}
		return this.response.usage();
	}
}
