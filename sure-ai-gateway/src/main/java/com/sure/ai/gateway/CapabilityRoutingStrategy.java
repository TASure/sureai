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

import java.util.ArrayList;
import java.util.List;

/**
 * 能力路由：只保留声明了本次调用所需能力的候选，再委托给兜底策略。
 *
 * <p>装饰器。例如请求需要 {@link com.sure.ai.client.Capability#EMBED} 时，
 * 未声明该能力的客户端会被过滤掉，避免把请求发给不支持嵌入的平台。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class CapabilityRoutingStrategy implements RoutingStrategy {

	/** 兜底策略。 */
	private final RoutingStrategy delegate;

	/**
	 * 构造器。
	 *
	 * @param delegate 能力过滤后的最终选择策略
	 */
	public CapabilityRoutingStrategy(RoutingStrategy delegate) {
		this.delegate = delegate == null ? new RoundRobinStrategy() : delegate;
	}

	@Override
	public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		if (context.capability() == null) {
			return this.delegate.select(context, candidates);
		}
		List<ClientCandidate> filtered = new ArrayList<>();
		candidates.stream().filter(c -> c.has(context.capability())).forEach(filtered::add);
		if (filtered.isEmpty()) {
			return null;
		}
		return this.delegate.select(context, filtered);
	}
}
