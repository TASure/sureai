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

import com.sure.ai.client.AiClient;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.cost.CostAggregator;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.cost.PriceCatalog;

/**
 * 网关静态入口工具类：对标各平台 {@code XxxUtil}（如 {@code OpenAiUtil}）的
 * {@link SingletonHolder} 单例装配模式。
 *
 * <p>默认装配即用：调用 {@link #getInstance()} 后通过 {@link #registry()} 注册下游 client，
 * 再用 {@link #gateway()} 拿到装配好的 {@link AiClient} 复合体直接对话。默认装配内容：</p>
 * <ul>
 *   <li>新建 {@link ClientRegistry}；</li>
 *   <li>默认组合路由策略（显式平台 → 能力过滤 → 轮询）；</li>
 *   <li>默认 {@link FailoverConfig}；</li>
 *   <li>默认 {@link CircuitBreakerRegistry}（3 次失败开窗、10s 冷却）；</li>
 *   <li>新建 {@link GatewayMetrics} 与 {@link GatewayAuditLogger}；</li>
 *   <li>默认 {@link CostCalculator}（内置 {@link PriceCatalog#defaults()} 价格目录）。</li>
 * </ul>
 *
 * <p>生产化组件可按需覆写：{@link #builder()} 逐项注入自定义熔断配置 / 指标 / 审计 / 成本计算器；
 * {@link Builder#tenant} 可选装配租户层（{@link TenantAwareGatewayClient} 预算 / 并发管控）；
 * {@link Builder#healthProbe} 可选装配 {@link HealthMonitor}（构建后处于停止态，
 * 调用 {@link #startHealthMonitor()} 启动定期探活）。</p>
 *
 * <p>{@link #init(GatewayUtil)} 可显式替换单例；{@link #reset()} 置空后下次 {@link #getInstance()}
 * 重新走默认装配（供测试与运行时重配）。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public final class GatewayUtil {

	/** 主装配容器（DCL 懒加载默认装配）。 */
	private static final SingletonHolder<GatewayUtil> HOLDER =
			new SingletonHolder<>(GatewayUtil::defaultAssembly);

	/** 路由表（注册下游 client 用）。 */
	private final ClientRegistry registry;

	/** 已装配的网关复合体（可选包裹租户层）。 */
	private final AiClient gateway;

	/** 指标聚合器（观测快照用）。 */
	private final GatewayMetrics metrics;

	/** 健康监视器（未装配探活时为 null）。 */
	private final HealthMonitor healthMonitor;

	private GatewayUtil(Builder b) {
		this.registry = b.registry == null ? new ClientRegistry() : b.registry;
		RoutingStrategy strategy = b.strategy == null
				? new ExplicitRoutingStrategy(
						new CapabilityRoutingStrategy(new RoundRobinStrategy()))
				: b.strategy;
		FailoverConfig failoverConfig = b.failoverConfig == null
				? FailoverConfig.defaults() : b.failoverConfig;
		CircuitBreakerRegistry breakers;
		if (b.breakers != null) {
			breakers = b.breakers;
		} else if (b.breakerConfig != null) {
			breakers = new CircuitBreakerRegistry(b.breakerConfig);
		} else {
			breakers = CircuitBreakerRegistry.defaults();
		}
		GatewayMetrics gwMetrics = b.metrics == null ? new GatewayMetrics() : b.metrics;
		GatewayAuditLogger auditLogger = b.auditLogger == null
				? new GatewayAuditLogger() : b.auditLogger;
		CostCalculator costCalculator = b.costCalculator == null
				? new CostCalculator(PriceCatalog.defaults()) : b.costCalculator;
		GatewayClient base = new GatewayClient(this.registry, strategy, failoverConfig,
				breakers, gwMetrics, auditLogger, costCalculator);
		AiClient assembled = base;
		if (b.tenantManager != null) {
			CostAggregator aggregator = b.costAggregator == null
					? new CostAggregator() : b.costAggregator;
			BudgetEnforcer enforcer = b.enforcer == null
					? new BudgetEnforcer(b.tenantManager, aggregator) : b.enforcer;
			assembled = new TenantAwareGatewayClient(base, enforcer, costCalculator);
		}
		this.gateway = assembled;
		this.metrics = gwMetrics;
		if (b.healthProbe != null) {
			long interval = b.healthIntervalMs > 0
					? b.healthIntervalMs : HealthMonitor.DEFAULT_INTERVAL_MS;
			this.healthMonitor = new HealthMonitor(this.registry, b.healthProbe,
					HealthMonitor.DEFAULT_UNHEALTHY_COOLDOWN_MS, interval);
			this.healthMonitor.breakers(breakers);
		} else {
			this.healthMonitor = null;
		}
	}

	private GatewayUtil() {
		throw new AssertionError("No instances");
	}

	/** 默认装配工厂（{@link SingletonHolder} 懒加载用）。 */
	private static GatewayUtil defaultAssembly() {
		return new Builder().build();
	}

	/**
	 * 获取单例装配；未初始化时按默认配置懒装配。
	 *
	 * @return 单例
	 */
	public static GatewayUtil getInstance() {
		return HOLDER.get();
	}

	/**
	 * 显式替换单例（自定义装配后注入）。
	 *
	 * @param util 已装配实例
	 */
	public static void init(GatewayUtil util) {
		HOLDER.set(util);
	}

	/** 置空单例，下次 {@link #getInstance()} 重新走默认装配（测试与重配用）。 */
	public static void reset() {
		HOLDER.reset();
	}

	/**
	 * 路由表（注册下游 client 用）。
	 *
	 * @return 注册表
	 */
	public ClientRegistry registry() {
		return this.registry;
	}

	/**
	 * 装配好的网关复合体：未装配租户层时即 {@link GatewayClient}，装配后为
	 * {@code TenantAwareGatewayClient} 包裹。
	 *
	 * @return 网关客户端
	 */
	public AiClient gateway() {
		return this.gateway;
	}

	/**
	 * 指标聚合器（观测快照用）。
	 *
	 * @return 指标聚合器
	 */
	public GatewayMetrics metrics() {
		return this.metrics;
	}

	/**
	 * 启动定期探活（幂等；未装配探活时无操作）。
	 */
	public void startHealthMonitor() {
		if (this.healthMonitor != null) {
			this.healthMonitor.start();
		}
	}

	/**
	 * 停止定期探活（幂等；未装配探活时无操作）。
	 */
	public void stopHealthMonitor() {
		if (this.healthMonitor != null) {
			this.healthMonitor.stop();
		}
	}

	/** 健康监视器（测试同包访问 runOnce 用；未装配返回 null）。 */
	HealthMonitor healthMonitor() {
		return this.healthMonitor;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * {@link GatewayUtil} 可变 Builder：逐项覆写默认装配，未覆写的字段走默认值。
	 */
	public static final class Builder {

		private ClientRegistry registry;

		private RoutingStrategy strategy;

		private FailoverConfig failoverConfig;

		private CircuitBreakerRegistry breakers;

		private CircuitBreakerConfig breakerConfig;

		private GatewayMetrics metrics;

		private GatewayAuditLogger auditLogger;

		private CostCalculator costCalculator;

		private TenantManager tenantManager;

		private CostAggregator costAggregator;

		private BudgetEnforcer enforcer;

		private HealthProbe healthProbe;

		private long healthIntervalMs;

		private Builder() {
		}

		/**
		 * 自定义注册表（默认新建空注册表）。
		 *
		 * @param registry 注册表
		 * @return this
		 */
		public Builder registry(ClientRegistry registry) {
			this.registry = registry;
			return this;
		}

		/**
		 * 自定义路由策略（默认：显式平台 → 能力过滤 → 轮询）。
		 *
		 * @param strategy 策略
		 * @return this
		 */
		public Builder strategy(RoutingStrategy strategy) {
			this.strategy = strategy;
			return this;
		}

		/**
		 * 自定义故障转移配置（默认 {@link FailoverConfig#defaults()}）。
		 *
		 * @param failoverConfig 配置
		 * @return this
		 */
		public Builder failoverConfig(FailoverConfig failoverConfig) {
			this.failoverConfig = failoverConfig;
			return this;
		}

		/**
		 * 自定义熔断器注册表（默认 {@link CircuitBreakerRegistry#defaults()}）。
		 *
		 * @param breakers 注册表
		 * @return this
		 */
		public Builder circuitBreakers(CircuitBreakerRegistry breakers) {
			this.breakers = breakers;
			return this;
		}

		/**
		 * 便捷熔断配置：内部包成 {@link CircuitBreakerRegistry}（与 {@link #circuitBreakers}
		 * 互斥，本项优先于默认值、次于显式 Registry）。
		 *
		 * @param config 熔断配置
		 * @return this
		 */
		public Builder circuitBreakerConfig(CircuitBreakerConfig config) {
			this.breakerConfig = config;
			return this;
		}

		/**
		 * 自定义指标聚合器（默认新建）。
		 *
		 * @param metrics 指标
		 * @return this
		 */
		public Builder metrics(GatewayMetrics metrics) {
			this.metrics = metrics;
			return this;
		}

		/**
		 * 自定义审计日志器（默认新建 JUL 审计器）。
		 *
		 * @param logger 审计日志器
		 * @return this
		 */
		public Builder auditLogger(GatewayAuditLogger logger) {
			this.auditLogger = logger;
			return this;
		}

		/**
		 * 自定义成本计算器（默认 {@link PriceCatalog#defaults()} 目录）。
		 *
		 * @param calculator 成本计算器
		 * @return this
		 */
		public Builder costCalculator(CostCalculator calculator) {
			this.costCalculator = calculator;
			return this;
		}

		/**
		 * 装配租户层：gateway() 返回 {@link TenantAwareGatewayClient} 包裹（预算 / 并发管控）。
		 *
		 * @param tenantManager   租户管理器
		 * @param costAggregator  成本汇总器（null 新建空汇总器）
		 * @param enforcer        预算执行器（null 按 tenantManager + aggregator 新建）
		 * @return this
		 */
		public Builder tenant(TenantManager tenantManager, CostAggregator costAggregator,
				BudgetEnforcer enforcer) {
			this.tenantManager = tenantManager;
			this.costAggregator = costAggregator;
			this.enforcer = enforcer;
			return this;
		}

		/**
		 * 装配健康探活（构建后监视器处于停止态，用 {@link GatewayUtil#startHealthMonitor()} 启动）。
		 *
		 * @param probe 探活 SPI
		 * @return this
		 */
		public Builder healthProbe(HealthProbe probe) {
			this.healthProbe = probe;
			return this;
		}

		/**
		 * 探活间隔毫秒（默认 {@link HealthMonitor#DEFAULT_INTERVAL_MS}）。
		 *
		 * @param intervalMs 间隔毫秒
		 * @return this
		 */
		public Builder healthIntervalMs(long intervalMs) {
			this.healthIntervalMs = intervalMs;
			return this;
		}

		/**
		 * 构建网关装配。
		 *
		 * @return 装配好的 GatewayUtil
		 */
		public GatewayUtil build() {
			return new GatewayUtil(this);
		}
	}
}
