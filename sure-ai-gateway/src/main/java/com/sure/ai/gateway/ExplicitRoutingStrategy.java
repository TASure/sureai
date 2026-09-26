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
 * 显式平台路由：调用方在请求中指定了目标平台时，只在该平台的实例中选择；
 * 未指定时委托给兜底策略。
 *
 * <p>显式平台来源见 {@link RequestContext#explicitPlatform()}：{@code extra["platform"]}
 * 或模型名 {@code "platform:model"} 前缀。这是装饰器——先收窄候选，再把收窄后的列表交给
 * {@code delegate} 做最终选择，从而与轮询/加权等自由选择策略自由组合。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class ExplicitRoutingStrategy implements RoutingStrategy {

	/** 兜底策略。 */
	private final RoutingStrategy delegate;

	/**
	 * 构造器。
	 *
	 * @param delegate 未指定显式平台时的兜底策略
	 */
	public ExplicitRoutingStrategy(RoutingStrategy delegate) {
		this.delegate = delegate == null ? new RoundRobinStrategy() : delegate;
	}

	@Override
	public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		String platform = context.explicitPlatform();
		if (platform == null) {
			return this.delegate.select(context, candidates);
		}
		List<ClientCandidate> filtered = new ArrayList<>();
		candidates.stream().filter(c -> platform.equals(c.platform())).forEach(filtered::add);
		if (filtered.isEmpty()) {
			return null;
		}
		return this.delegate.select(context, filtered);
	}
}
