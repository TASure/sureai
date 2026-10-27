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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询路由：按调用顺序在候选间循环分配请求。
 *
 * <p>线程安全：内部使用 {@link AtomicInteger} 计数器，高并发下仍近似均匀分布。
 * 这是叶子策略，通常作为装饰器链的最末一环。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class RoundRobinStrategy implements RoutingStrategy {

	/** 轮转计数器。 */
	private final AtomicInteger counter = new AtomicInteger();

	@Override
	public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		int idx = Math.floorMod(this.counter.getAndIncrement(), candidates.size());
		return candidates.get(idx);
	}
}
