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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

import com.sure.ai.cost.CostAggregator;
import com.sure.ai.exception.AiBudgetExceededException;
import com.sure.ai.model.TokenUsage;
import com.sure.tool.thread.RateLimiter;

/**
 * 租户预算执行器：调用前做配额/预算/QPS 检查，调用后记录实际成本与 token。
 *
 * <h2>职责拆分</h2>
 * <ul>
 *   <li><b>本类自管</b>：按预算周期滚动的窗口用量（成本 / token / 请求数）与 QPS 限流——
 *       {@link CostAggregator} 只做跨周期全量统计，不支持“按租户按窗口”查询，故窗口用量由本类维护。</li>
 *   <li><b>复用成本计量</b>：每次成功调用后把 {@code (tenantId, model, usage, cost)} 透传给
 *       {@link CostAggregator#record}，供全量成本报表使用。</li>
 * </ul>
 *
 * <h2>限流实现</h2>
 * <p>QPS 复用 sure-core 的令牌桶 {@link RateLimiter}（非阻塞 {@code tryAcquire}），按租户懒加载。
 * 预算周期到期后窗口用量清零，但令牌桶按秒平滑补充、不随窗口重置。</p>
 *
 * <p>时钟可注入（默认 {@link System#currentTimeMillis()}），便于测试周期重置。线程安全。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class BudgetEnforcer {

	/** 租户管理器。 */
	private final TenantManager tenantManager;

	/** 共享成本汇总器。 */
	private final CostAggregator costAggregator;

	/** 时钟（epoch 毫秒）。 */
	private final LongSupplier clock;

	/** 租户 → 窗口状态。 */
	private final ConcurrentMap<String, TenantState> states = new ConcurrentHashMap<>();

	/**
	 * 全参构造器。
	 *
	 * @param tenantManager  租户管理器
	 * @param costAggregator 成本汇总器
	 * @param clock          时钟（返回 epoch 毫秒）
	 */
	public BudgetEnforcer(TenantManager tenantManager, CostAggregator costAggregator,
			LongSupplier clock) {
		this.tenantManager = tenantManager;
		this.costAggregator = costAggregator;
		this.clock = clock == null ? System::currentTimeMillis : clock;
	}

	/**
	 * 构造器：系统时钟。
	 *
	 * @param tenantManager  租户管理器
	 * @param costAggregator 成本汇总器
	 */
	public BudgetEnforcer(TenantManager tenantManager, CostAggregator costAggregator) {
		this(tenantManager, costAggregator, System::currentTimeMillis);
	}

	/**
	 * 调用前检查：租户超限则抛 {@link AiBudgetExceededException}。
	 *
	 * <p>tenantId 为空白或租户未配置时不做任何限制（直接放行）。</p>
	 *
	 * @param tenantId      租户 ID
	 * @param estimatedCost 本次预估成本（USD）；无法估计传 0
	 */
	public void checkBeforeCall(String tenantId, double estimatedCost) {
		if (tenantId == null || tenantId.isBlank()) {
			return;
		}
		TenantConfig cfg = this.tenantManager.configFor(tenantId);
		if (cfg == null) {
			return;
		}
		TenantState st = stateFor(tenantId);
		synchronized (st) {
			st.enter(this.clock.getAsLong(), cfg);

			if (cfg.limitedQps() && !st.limiter(cfg.rateLimitQps()).tryAcquire()) {
				throw new AiBudgetExceededException(tenantId,
						"Tenant '" + tenantId + "' exceeded rate limit: qps=" + cfg.rateLimitQps());
			}
			if (cfg.limitedCost() && st.costUsed + estimatedCost > cfg.maxCostPerPeriod()) {
				throw new AiBudgetExceededException(tenantId,
						"Tenant '" + tenantId + "' has exceeded budget: used $"
								+ String.format("%.4f", st.costUsed) + " / $"
								+ String.format("%.4f", cfg.maxCostPerPeriod()));
			}
			if (cfg.limitedTokens() && st.tokensUsed >= cfg.maxTokensPerPeriod()) {
				throw new AiBudgetExceededException(tenantId,
						"Tenant '" + tenantId + "' has exceeded token budget: used "
								+ st.tokensUsed + " / " + cfg.maxTokensPerPeriod());
			}
		}
	}

	/**
	 * 调用成功后记录实际成本与 token（同时写入共享 {@link CostAggregator}）。
	 *
	 * @param tenantId   租户 ID
	 * @param model      模型名
	 * @param usage      实际 token 用量（可 null，按 0 处理）
	 * @param actualCost 实际成本（USD）
	 */
	public void recordAfterCall(String tenantId, String model, TokenUsage usage, double actualCost) {
		if (tenantId == null || tenantId.isBlank()) {
			return;
		}
		TokenUsage safeUsage = usage == null ? TokenUsage.of(0, 0, 0) : usage;
		TenantConfig cfg = this.tenantManager.configFor(tenantId);
		TenantState st = stateFor(tenantId);
		synchronized (st) {
			st.enter(this.clock.getAsLong(), cfg == null ? TenantConfig.unlimited() : cfg);
			st.costUsed += actualCost;
			st.tokensUsed += safeUsage.totalTokens();
			st.requestsInWindow++;
		}
		if (model != null && !model.isBlank()) {
			this.costAggregator.record(tenantId, model, safeUsage, actualCost);
		}
	}

	/**
	 * 查询租户当前用量快照。
	 *
	 * @param tenantId 租户 ID
	 * @return 用量快照
	 */
	public TenantUsage currentUsage(String tenantId) {
		TenantConfig cfg = this.tenantManager.configFor(tenantId);
		if (tenantId == null || tenantId.isBlank()) {
			return new TenantUsage(0, 0, 0, 0, 0, 0);
		}
		TenantState st = this.states.get(tenantId);
		double cost = 0d;
		long tokens = 0L;
		int requests = 0;
		if (st != null) {
			synchronized (st) {
				cost = st.costUsed;
				tokens = st.tokensUsed;
				requests = st.requestsInWindow;
			}
		}
		double costLimit = cfg == null ? 0d : cfg.maxCostPerPeriod();
		long tokenLimit = cfg == null ? 0L : cfg.maxTokensPerPeriod();
		int qpsLimit = cfg == null ? 0 : cfg.rateLimitQps();
		return new TenantUsage(cost, tokens, requests, costLimit, tokenLimit, qpsLimit);
	}

	/**
	 * 取（或创建）租户窗口状态。
	 *
	 * @param tenantId 租户 ID
	 * @return 状态
	 */
	private TenantState stateFor(String tenantId) {
		return this.states.computeIfAbsent(tenantId, k -> new TenantState());
	}

	/** 租户窗口可变状态（外部方法内 synchronized 保护）。 */
	private static final class TenantState {

		/** 窗口起始 epoch 毫秒；&lt;0 表示尚未开始（避免与 epoch 0 冲突）。 */
		private long windowStartMs = -1L;

		/** 本窗口累计成本（USD）。 */
		private double costUsed;

		/** 本窗口累计 token。 */
		private long tokensUsed;

		/** 本窗口请求数。 */
		private int requestsInWindow;

		/** QPS 令牌桶，懒加载。 */
		private RateLimiter limiter;

		/**
		 * 进入窗口：若周期已过则清零。
		 *
		 * @param now 当前 epoch 毫秒
		 * @param cfg 租户配置
		 */
		void enter(long now, TenantConfig cfg) {
			long periodMs = cfg.budgetPeriod().toMillis();
			if (this.windowStartMs < 0L) {
				this.windowStartMs = now;
			} else if (now - this.windowStartMs >= periodMs) {
				this.windowStartMs = now;
				this.costUsed = 0d;
				this.tokensUsed = 0L;
				this.requestsInWindow = 0;
			}
		}

		/**
		 * 懒加载令牌桶。
		 *
		 * @param qps 每秒许可数
		 * @return 限流器
		 */
		RateLimiter limiter(int qps) {
			if (this.limiter == null) {
				this.limiter = new RateLimiter(qps);
			}
			return this.limiter;
		}
	}
}
