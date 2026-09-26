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

package com.sure.ai.gateway;

import java.util.List;

import com.sure.ai.cost.PriceCatalog;
import com.sure.ai.cost.PriceCatalog.ModelPrice;

/**
 * 最低成本路由：选择输入单价最低的候选。
 *
 * <p>对每个候选确定其计价模型：优先用注册时声明的 {@link ClientCandidate#defaultModel()}，
 * 否则用请求裸模型名（去掉 {@code platform:} 前缀）。再用 {@link PriceCatalog} 查询该模型的
 * {@code inputPer1k}；目录中无价格的候选视为最贵（{@link Double#POSITIVE_INFINITY}），
 * 以避免把请求路由到未知成本的实例。叶子策略。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class LowestCostStrategy implements RoutingStrategy {

	/** 价格目录。 */
	private final PriceCatalog catalog;

	/**
	 * 构造器。
	 *
	 * @param catalog 模型价格目录
	 */
	public LowestCostStrategy(PriceCatalog catalog) {
		this.catalog = catalog == null ? PriceCatalog.defaults() : catalog;
	}

	@Override
	public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		ClientCandidate best = null;
		double bestPrice = Double.POSITIVE_INFINITY;
		for (ClientCandidate c : candidates) {
			String model = c.defaultModel() != null ? c.defaultModel() : context.bareModel();
			double price = priceOf(model);
			if (price < bestPrice) {
				bestPrice = price;
				best = c;
			}
		}
		return best;
	}

	private double priceOf(String model) {
		if (model == null || !this.catalog.hasPrice(model)) {
			return Double.POSITIVE_INFINITY;
		}
		ModelPrice p = this.catalog.priceFor(model);
		return p.inputPer1k();
	}
}
