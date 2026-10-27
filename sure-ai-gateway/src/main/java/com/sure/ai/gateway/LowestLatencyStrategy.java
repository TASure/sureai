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

/**
 * 最低延迟路由：选择滑动窗口内平均延迟最低的候选。
 *
 * <p>延迟数据来自注入的 {@link LatencyTracker}（由 {@link GatewayClient} 在每次成功调用后回写）。
 * 无历史样本的候选使用 {@link LatencyTracker#DEFAULT_LATENCY_MS}，略高于真实值，
 * 使已被验证的快速实例优先、冷实例仍可在全部冷启动时被选中。叶子策略。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class LowestLatencyStrategy implements RoutingStrategy {

	/** 延迟观测器。 */
	private final LatencyTracker tracker;

	/**
	 * 构造器。
	 *
	 * @param tracker 延迟观测器
	 */
	public LowestLatencyStrategy(LatencyTracker tracker) {
		this.tracker = tracker == null ? new LatencyTracker() : tracker;
	}

	@Override
	public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		ClientCandidate best = null;
		double bestLatency = Double.MAX_VALUE;
		for (ClientCandidate c : candidates) {
			double lat = this.tracker.averageLatency(c.platform(), c.instanceId());
			if (lat < bestLatency) {
				bestLatency = lat;
				best = c;
			}
		}
		return best;
	}
}
