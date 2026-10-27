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

/**
 * 租户当前用量快照（不可变）。
 *
 * <p>限额字段为 {@code 0} 表示该维度不限。</p>
 *
 * @param costUsed          本周期已用成本（USD）
 * @param tokensUsed        本周期已用 token 数
 * @param requestsInWindow  本周期已发起请求数
 * @param costLimit         周期成本上限（0=不限）
 * @param tokenLimit        周期 token 上限（0=不限）
 * @param qpsLimit          每秒请求上限（0=不限）
 * @author sureai
 * @since 1.6.0
 */
public record TenantUsage(double costUsed, long tokensUsed, int requestsInWindow,
		double costLimit, long tokenLimit, int qpsLimit) {
}
