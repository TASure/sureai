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

/**
 * 成本计量能力：价格目录（{@link com.sure.ai.cost.PriceCatalog}）、
 * 单次成本计算（{@link com.sure.ai.cost.CostCalculator}）、
 * 按租户/模型/时间窗汇总（{@link com.sure.ai.cost.CostAggregator}）、
 * 以及与 {@code MetricsCollector} 集成的 {@link com.sure.ai.cost.CostMetricsCollector}。
 *
 * <p>供 Gateway 模块的最低成本路由和租户预算使用。纯 JDK 实现，零新依赖。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
package com.sure.ai.cost;
