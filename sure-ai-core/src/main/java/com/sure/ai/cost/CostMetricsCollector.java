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

package com.sure.ai.cost;

import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.model.TokenUsage;
import com.sure.tool.lang.Assert;

/**
 * 成本指标采集器：把 {@link MetricsCollector#onTokenUsage} 回调自动接入成本计算与汇总。
 *
 * <p>用法：在 Gateway 中把本实例作为 {@code MetricsCollector} 挂载；请求处理前调用
 * {@link #setCurrentTenantId(String)} 设置当前租户，请求结束后调用 {@link #clearCurrentTenantId()}
 * 清理 ThreadLocal。{@link #onTokenUsage(String, long, long, long)} 被回调时，
 * 自动用 {@link CostCalculator} 算出成本并记录到 {@link CostAggregator}。</p>
 *
 * <p>未设置租户时默认归入 {@value #DEFAULT_TENANT}。
 * 本类不修改 {@link MetricsCollector} 接口，纯包装实现。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class CostMetricsCollector implements MetricsCollector {

	/** 未设置 ThreadLocal 时使用的默认租户。 */
	public static final String DEFAULT_TENANT = "default";

	private static final ThreadLocal<String> TENANT_HOLDER = new ThreadLocal<>();

	private final CostCalculator calculator;
	private final CostAggregator aggregator;

	/**
	 * 构造。
	 *
	 * @param catalog    价格目录
	 * @param aggregator 成本汇总器
	 */
	public CostMetricsCollector(PriceCatalog catalog, CostAggregator aggregator) {
		Assert.notNull(catalog, "catalog must not be null");
		Assert.notNull(aggregator, "aggregator must not be null");
		this.calculator = new CostCalculator(catalog);
		this.aggregator = aggregator;
	}

	/**
	 * 设置当前线程的租户 ID（Gateway 过滤器在请求入口调用）。
	 *
	 * @param tenantId 租户 ID
	 */
	public static void setCurrentTenantId(String tenantId) {
		Assert.notBlank(tenantId, "tenantId must not be blank");
		TENANT_HOLDER.set(tenantId);
	}

	/**
	 * 清理当前线程的租户 ID（Gateway 过滤器在请求 finally 中调用）。
	 */
	public static void clearCurrentTenantId() {
		TENANT_HOLDER.remove();
	}

	/**
	 * 获取当前线程的租户 ID（未设置返回 {@link #DEFAULT_TENANT}）。
	 *
	 * @return 当前租户 ID
	 */
	public static String getCurrentTenantId() {
		String t = TENANT_HOLDER.get();
		return t != null ? t : DEFAULT_TENANT;
	}

	/**
	 * 回调 token 用量：自动计算成本并记录到 aggregator。
	 *
	 * @param model           模型名
	 * @param promptTokens    提示 token 数
	 * @param completionTokens 补全 token 数
	 * @param totalTokens     总 token 数
	 */
	@Override
	public void onTokenUsage(String model, long promptTokens, long completionTokens, long totalTokens) {
		TokenUsage usage = TokenUsage.of((int) promptTokens, (int) completionTokens, (int) totalTokens);
		double cost = this.calculator.calculate(model, usage);
		this.aggregator.record(getCurrentTenantId(), model, usage, cost);
	}
}
