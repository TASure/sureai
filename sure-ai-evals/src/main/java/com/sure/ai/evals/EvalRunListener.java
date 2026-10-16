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

import java.util.Map;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.MetricResult;

/**
 * 评估运行监听器：用例级生命周期钩子（录制 / 观测扩展点）。
 *
 * <p>本批只定义钩子接口，不实现完整录制；批2 的 {@code RecordingClient} 将通过实现本接口
 * 把请求 / 响应落盘，供回放与基线回归使用。所有方法默认空实现，实现方无需关心不感兴趣的事件。
 * 钩子内部抛出的异常会被 runner 捕获并忽略，不得影响评估主流程。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public interface EvalRunListener {

	/**
	 * 用例开始执行前回调。
	 *
	 * @param evalCase 用例
	 */
	default void onCaseStarted(EvalCase evalCase) {
	}

	/**
	 * 用例执行与全部指标评估完成后回调。
	 *
	 * @param evalCase 用例
	 * @param outcome  执行结果
	 * @param results  指标名 → 结果
	 */
	default void onCaseComplete(EvalCase evalCase, EvalOutcome outcome,
			Map<String, MetricResult> results) {
	}
}
