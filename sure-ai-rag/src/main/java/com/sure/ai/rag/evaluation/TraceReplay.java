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

import java.util.List;

import com.sure.tool.lang.Assert;

/**
 * 轨迹回放器：从 {@link TraceStore} 或 JSON 读取历史轨迹，用指定评估器重跑指标。
 *
 * <p>典型用途：离线回归与跨版本对比——保存基线轨迹后，在新版检索/生成逻辑下重新评估，
 * 再用 {@link EvaluationAssertions#assertMeets} 对比阈值或历史基线。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class TraceReplay {

	private final RagEvaluator evaluator;

	/**
	 * 构造回放器。
	 *
	 * @param evaluator 评估器
	 */
	public TraceReplay(RagEvaluator evaluator) {
		Assert.notNull(evaluator, "evaluator 不能为 null");
		this.evaluator = evaluator;
	}

	/**
	 * 回放存储中的全部轨迹，返回每条轨迹的逐次评估结果。
	 *
	 * @param store 轨迹存储
	 * @return 与轨迹一一对应的评估结果列表
	 */
	public List<EvaluationResult> replay(TraceStore store) {
		Assert.notNull(store, "store 不能为 null");
		List<RagTrace> traces = store.all();
		return traces.stream().map(this.evaluator::evaluate).toList();
	}

	/**
	 * 回放存储中的全部轨迹，返回批量汇总结果。
	 *
	 * @param store 轨迹存储
	 * @return 汇总评估结果
	 */
	public EvaluationResult replayAggregate(TraceStore store) {
		Assert.notNull(store, "store 不能为 null");
		return this.evaluator.evaluateAll(store.all());
	}

	/**
	 * 从 JSON 数组文本回放轨迹，返回每条轨迹的逐次评估结果。
	 *
	 * @param jsonList {@link TraceSerializer#toJsonList} 产出的 JSON 数组文本
	 * @return 逐次评估结果列表
	 */
	public List<EvaluationResult> replayFromJson(String jsonList) {
		Assert.notNull(jsonList, "jsonList 不能为 null");
		List<RagTrace> traces = TraceSerializer.fromJsonList(jsonList);
		return traces.stream().map(this.evaluator::evaluate).toList();
	}

	/**
	 * 从 JSON 数组文本回放轨迹，返回批量汇总结果。
	 *
	 * @param jsonList {@link TraceSerializer#toJsonList} 产出的 JSON 数组文本
	 * @return 汇总评估结果
	 */
	public EvaluationResult replayAggregateFromJson(String jsonList) {
		Assert.notNull(jsonList, "jsonList 不能为 null");
		return this.evaluator.evaluateAll(TraceSerializer.fromJsonList(jsonList));
	}
}
