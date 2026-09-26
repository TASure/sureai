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
 * 路由策略：从健康候选列表中选出本次调用使用的 {@link ClientCandidate}。
 *
 * <p>策略可通过装饰器模式组合：过滤器型策略（显式平台 / 能力）先收窄候选列表，
 * 再委托给叶子型策略（轮询 / 加权 / 最低延迟 / 最低成本）做最终选择。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public interface RoutingStrategy {

	/**
	 * 选出一个候选。
	 *
	 * @param context  路由上下文
	 * @param candidates 当前健康的候选列表（非空）
	 * @return 选中的候选；候选列表为空时返回 null
	 */
	ClientCandidate select(RequestContext context, List<ClientCandidate> candidates);
}
