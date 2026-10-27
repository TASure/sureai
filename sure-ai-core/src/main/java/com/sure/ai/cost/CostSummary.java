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

package com.sure.ai.cost;

import java.util.Map;

/**
 * 成本汇总快照（不可变）。
 *
 * @param totalCalls          总调用次数
 * @param totalPromptTokens   总输入 token 数
 * @param totalCompletionTokens 总输出 token 数
 * @param totalCost           总成本（USD）
 * @param tokensByModel       按模型拆分的 token 数（model → prompt+completion tokens）
 * @param costByModel         按模型拆分的成本（model → USD）
 * @author sureai
 * @since 1.6.0
 */
public record CostSummary(long totalCalls, long totalPromptTokens,
		long totalCompletionTokens, double totalCost,
		Map<String, Long> tokensByModel, Map<String, Double> costByModel) {

	/** 紧凑构造器：对 Map 做防御性拷贝，保证快照不可变。 */
	public CostSummary {
		tokensByModel = Map.copyOf(tokensByModel);
		costByModel = Map.copyOf(costByModel);
	}

	/**
	 * 空快照。
	 */
	public static final CostSummary EMPTY = new CostSummary(0, 0, 0, 0.0, Map.of(), Map.of());
}
