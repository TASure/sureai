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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 加权随机路由：按注册时声明的权重按概率选择候选。
 *
 * <p>每个候选的被选中概率 ≈ {@code weight / sum(weights)}。未指定权重时默认 1.0
 * （退化为均匀随机）。叶子策略。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class WeightedRoutingStrategy implements RoutingStrategy {

	@Override
	public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		double total = 0d;
		for (ClientCandidate c : candidates) {
			total += c.weight();
		}
		double r = ThreadLocalRandom.current().nextDouble(total);
		double acc = 0d;
		for (ClientCandidate c : candidates) {
			acc += c.weight();
			if (r < acc) {
				return c;
			}
		}
		return candidates.get(candidates.size() - 1);
	}
}
