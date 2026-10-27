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

import com.sure.ai.client.resilience.CircuitBreaker;

/**
 * 单个 client 的健康视图快照（不可变）。
 *
 * @param platform       平台名
 * @param instanceId     实例 ID
 * @param healthy        当前是否在路由健康集合内（综合冷却期判断）
 * @param circuitState   熔断器状态；{@code null} 表示该 client 未配置熔断
 * @param probeHealthy   最近一次探活结果；{@code null} 表示从未探活
 * @param lastProbeAtMs  最近一次探活时间戳（毫秒 epoch），0 表示从未探活
 * @author sureai
 * @since 2.7.0
 */
public record ClientHealthView(String platform, String instanceId, boolean healthy,
		CircuitBreaker.State circuitState, Boolean probeHealthy, long lastProbeAtMs) {
}
