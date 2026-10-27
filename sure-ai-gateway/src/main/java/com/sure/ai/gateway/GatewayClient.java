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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;
import com.sure.ai.client.resilience.CircuitBreaker;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.TokenUsage;

/**
 * 对调用方透明的 AI 网关客户端：实现 {@link AiClient}，内部按路由策略选择下游供应商实例，
 * 失败时自动故障转移。
 *
 * <p>调用方拿到的就是一个普通 {@link AiClient}——无需感知背后注册了哪些平台、用了什么策略。
 * {@link #name()} 返回 {@code "gateway"}。</p>
 *
 * <h2>每次调用的执行流程</h2>
 * <ol>
 *   <li>从请求构建 {@link RequestContext}（方法、能力、模型、extra）；</li>
 *   <li>从注册表取当前<b>健康</b>候选；若装配了 {@link CircuitBreakerRegistry}，再过滤掉熔断
 *       OPEN 的候选（被过滤者记入本次 tried 并触发 {@link GatewayMetrics#onCircuitReject}）；
 *       若全部候选都被熔断拒绝，直接快速失败（异常消息含 {@code circuit breaker} 字样）；</li>
 *   <li>路由策略从可用候选中选出一个并调用；</li>
 *   <li>成功：记录延迟、恢复健康、熔断 onSuccess、{@link GatewayMetrics#onRequestSuccess}、
 *       审计 SUCCESS 事件（含成本估算），返回结果；</li>
 *   <li>失败：熔断 onFailure、{@link GatewayMetrics#onRequestFailure}、审计 FAILED_OVER 事件；
 *       若异常可转移（超时/5xx/连接错误），摘除该实例并切下一候选重试；
 *       若不可转移（4xx），记审计 FAILED 后直接抛出；达到 maxAttempts 或无可用候选则抛聚合异常，
 *       终态再记一条审计 FAILED。</li>
 * </ol>
 *
 * <p><b>流式首段前转移</b>：{@link #chatStream} 使用独立模板 {@code executeStream}——
 * 下游客户端投递第一片之前抛异常可正常故障转移（与 {@code execute} 相同的熔断过滤 / 计数 / 审计钩子）；
 * 第一片已投递后下游再抛异常则原样抛出、<b>不转移</b>（避免向调用方重复投递内容）。
 * 流式路径成本恒记 0（分片不带累计用量，与 {@code TenantAwareGatewayClient} 流式限制一致）。</p>
 *
 * <p>全量构造器之后的四个生产化组件（熔断 / 指标 / 审计 / 成本）均可为 {@code null}——
 * {@code null} 时对应横切逻辑整体跳过、零额外开销，既有两参构造器行为与历史完全一致。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class GatewayClient implements AiClient {

	/** 路由表。 */
	private final ClientRegistry registry;

	/** 路由策略。 */
	private final RoutingStrategy strategy;

	/** 故障转移配置。 */
	private final FailoverConfig failoverConfig;

	/** 延迟观测（每次成功调用回写）。 */
	private final LatencyTracker latencyTracker = new LatencyTracker();

	/** 熔断器注册表（可空；null 时不做熔断过滤）。 */
	private final CircuitBreakerRegistry circuitBreakers;

	/** 指标聚合器（可空）。 */
	private final GatewayMetrics metrics;

	/** 审计日志器（可空）。 */
	private final GatewayAuditLogger auditLogger;

	/** 成本计算器（可空）。 */
	private final CostCalculator costCalculator;

	/** 审计事件请求 ID 计数器（单调递增）。 */
	private final AtomicLong requestIds = new AtomicLong();

	/**
	 * 全量构造器（含生产化横切组件）。
	 *
	 * <p>四个生产化组件均可为 {@code null}：{@code null} 时对应熔断过滤 / 指标 / 审计 / 成本
	 * 估算整体跳过，行为与历史构造器完全一致。</p>
	 *
	 * @param registry         注册中心
	 * @param strategy         路由策略（null 使用默认组合：显式平台 → 能力过滤 → 轮询）
	 * @param failoverConfig   故障转移配置（null 使用 {@link FailoverConfig#defaults()}）
	 * @param circuitBreakers  熔断器注册表（可空）
	 * @param metrics          指标聚合器（可空）
	 * @param auditLogger      审计日志器（可空）
	 * @param costCalculator   成本计算器（可空）
	 */
	public GatewayClient(ClientRegistry registry, RoutingStrategy strategy,
			FailoverConfig failoverConfig, CircuitBreakerRegistry circuitBreakers,
			GatewayMetrics metrics, GatewayAuditLogger auditLogger,
			CostCalculator costCalculator) {
		this.registry = registry;
		this.strategy = strategy == null ? defaultStrategy() : strategy;
		this.failoverConfig = failoverConfig == null ? FailoverConfig.defaults() : failoverConfig;
		this.circuitBreakers = circuitBreakers;
		this.metrics = metrics;
		this.auditLogger = auditLogger;
		this.costCalculator = costCalculator;
	}

	/**
	 * 全量构造器（历史签名，生产化组件传 null，行为与历史一致）。
	 *
	 * @param registry       注册中心
	 * @param strategy       路由策略
	 * @param failoverConfig 故障转移配置
	 */
	public GatewayClient(ClientRegistry registry, RoutingStrategy strategy,
			FailoverConfig failoverConfig) {
		this(registry, strategy, failoverConfig, null, null, null, null);
	}

	/**
	 * 便捷构造器：默认组合策略（显式平台 → 能力过滤 → 轮询）+ 默认故障转移配置。
	 *
	 * @param registry 注册中心
	 */
	public GatewayClient(ClientRegistry registry) {
		this(registry, null, null);
	}

	/** 默认组合策略：显式平台 → 能力过滤 → 轮询。 */
	private static RoutingStrategy defaultStrategy() {
		return new ExplicitRoutingStrategy(new CapabilityRoutingStrategy(new RoundRobinStrategy()));
	}

	@Override
	public String name() {
		return "gateway";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		return execute(contextOf(request), client -> client.chat(request));
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		executeStream(contextOf(request), request, consumer);
	}

	@Override
	public void close() {
		this.registry.all().forEach(AiClient::close);
	}

	/**
	 * 汇总全部注册实例的健康视图（路由健康 + 熔断状态维度）。
	 *
	 * <p>基于 {@link ClientRegistry#candidates()} 枚举全部实例：{@code healthy} 取注册表冷却期判断，
	 * {@code circuitState} 取 {@link CircuitBreakerRegistry#snapshots()}（未装配熔断或该实例未创建
	 * 熔断器时为 null）。<b>不含探活维度</b>——{@code probeHealthy} 恒为 null、{@code lastProbeAtMs}
	 * 恒为 0；探活结果由 {@link HealthMonitor#healthView()} 负责汇总（两者可组合使用）。</p>
	 *
	 * @return 健康视图列表（按注册表枚举顺序）
	 */
	public List<ClientHealthView> healthView() {
		Map<String, CircuitBreaker.Snapshot> snaps = this.circuitBreakers == null
				? Map.of() : this.circuitBreakers.snapshots();
		List<ClientHealthView> out = new ArrayList<>();
		for (ClientCandidate c : this.registry.candidates()) {
			boolean healthy = this.registry.isHealthy(c.platform(), c.instanceId());
			CircuitBreaker.Snapshot snap = snaps.get(c.platform() + '|' + c.instanceId());
			CircuitBreaker.State state = snap == null ? null : snap.state();
			out.add(new ClientHealthView(c.platform(), c.instanceId(), healthy, state, null, 0L));
		}
		return out;
	}

	/** 从请求构建路由上下文。 */
	private RequestContext contextOf(ChatRequest request) {
		Capability cap = request.stream() ? Capability.CHAT_STREAM : Capability.CHAT;
		Object tenant = request.extra().get(RequestContext.EXTRA_TENANT_ID);
		return new RequestContext("chat", cap, request.model(), request.extra(),
				tenant instanceof String s ? s : null);
	}

	/**
	 * 故障转移执行模板（同步请求）。
	 *
	 * <p>横切顺序：进入循环前 {@code onRequestStart}；循环内先做熔断过滤再选候选；
	 * 成功路径记录延迟 / 健康 / 熔断 onSuccess / 指标 / 审计 SUCCESS（含成本）；
	 * 异常路径熔断 onFailure / 指标 onRequestFailure，不可转移记审计 FAILED 后直抛，
	 * 可转移记审计 FAILED_OVER 后摘除实例切下一候选；耗尽时记审计 FAILED 并抛聚合异常。</p>
	 */
	private <T> T execute(RequestContext ctx, Function<AiClient, T> action) {
		List<ClientCandidate> tried = new ArrayList<>();
		List<Throwable> suppressed = new ArrayList<>();
		Exception lastCause = null;
		ClientCandidate lastAttempted = null;
		int attempt = 0;
		long totalStart = System.nanoTime();
		if (this.metrics != null) {
			this.metrics.onRequestStart(ctx.method(), ctx.model());
		}

		while (attempt < this.failoverConfig.maxAttempts()) {
			List<ClientCandidate> candidates = this.registry.healthyCandidates();
			if (candidates.isEmpty()) {
				break;
			}
			List<ClientCandidate> usable = filterUsable(candidates, tried, suppressed);
			if (usable.isEmpty()) {
				// 全熔断快速失败：本轮所有候选均被熔断拒绝
				lastCause = lastSuppressed(suppressed);
				break;
			}
			ClientCandidate chosen = this.strategy.select(ctx, usable);
			if (chosen == null) {
				break;
			}
			attempt++;
			lastAttempted = chosen;
			long start = System.nanoTime();
			try {
				T result = action.apply(chosen.client());
				long latencyMs = elapsedMs(start);
				this.latencyTracker.record(chosen.platform(), chosen.instanceId(), latencyMs);
				this.registry.markHealthy(chosen.platform(), chosen.instanceId());
				if (this.circuitBreakers != null) {
					this.circuitBreakers.onSuccess(chosen.platform(), chosen.instanceId());
				}
				if (this.metrics != null) {
					this.metrics.onRequestSuccess(ctx.method(), ctx.model(), latencyMs);
				}
				double cost = estimateCost(ctx, result);
				if (this.metrics != null) {
					this.metrics.recordCost(cost);
				}
				audit(AuditEvent.Status.SUCCESS, ctx, chosen, latencyMs, cost, null);
				return result;
			} catch (Exception ex) {
				tried.add(chosen);
				suppressed.add(ex);
				lastCause = ex;
				long latencyMs = elapsedMs(start);
				if (this.circuitBreakers != null) {
					this.circuitBreakers.onFailure(chosen.platform(), chosen.instanceId());
				}
				if (this.metrics != null) {
					this.metrics.onRequestFailure(ctx.method(), ctx.model(), latencyMs);
				}
				if (!this.failoverConfig.isRetryable(ex)) {
					audit(AuditEvent.Status.FAILED, ctx, chosen, latencyMs, 0.0d, detailOf(ex));
					throw ex;
				}
				this.registry.markUnhealthy(chosen.platform(), chosen.instanceId(),
						this.failoverConfig.unhealthyCooldownMs());
				audit(AuditEvent.Status.FAILED_OVER, ctx, chosen, latencyMs, 0.0d, detailOf(ex));
				ClientCandidate next = nextCandidate(ctx);
				this.failoverConfig.listener().onFailover(
						chosen.platform(), chosen.instanceId(),
						next == null ? null : next.platform(),
						next == null ? null : next.instanceId(), ex, attempt);
			}
		}

		long totalMs = elapsedMs(totalStart);
		if (lastCause != null && lastAttempted != null) {
			audit(AuditEvent.Status.FAILED, ctx, lastAttempted, totalMs, 0.0d, detailOf(lastCause));
		}
		throw exhausted(tried, suppressed, lastCause, attempt);
	}

	/**
	 * 故障转移执行模板（流式请求）：首段投递之前允许转移，首段投递之后异常原样抛出不转移。
	 *
	 * <p>包装真实 consumer：收到第一片之前异常可转移（熔断过滤 / 计数 / 审计钩子与
	 * {@code execute} 一致）；第一片已投递后下游抛异常立即原样抛出（不转移、不重复投递），
	 * 也不再记熔断 / 指标副作用——此时实例本身是健康的（已成功产出首片），失败发生在调用方侧。
	 * 流式路径成本恒记 0（分片不带累计用量）。</p>
	 */
	private void executeStream(RequestContext ctx, ChatRequest request,
			Consumer<ChatStreamChunk> consumer) {
		List<ClientCandidate> tried = new ArrayList<>();
		List<Throwable> suppressed = new ArrayList<>();
		Exception lastCause = null;
		ClientCandidate lastAttempted = null;
		int attempt = 0;
		long totalStart = System.nanoTime();
		if (this.metrics != null) {
			this.metrics.onRequestStart(ctx.method(), ctx.model());
		}

		while (attempt < this.failoverConfig.maxAttempts()) {
			List<ClientCandidate> candidates = this.registry.healthyCandidates();
			if (candidates.isEmpty()) {
				break;
			}
			List<ClientCandidate> usable = filterUsable(candidates, tried, suppressed);
			if (usable.isEmpty()) {
				lastCause = lastSuppressed(suppressed);
				break;
			}
			ClientCandidate chosen = this.strategy.select(ctx, usable);
			if (chosen == null) {
				break;
			}
			attempt++;
			lastAttempted = chosen;
			long start = System.nanoTime();
			AtomicBoolean firstSent = new AtomicBoolean(false);
			try {
				chosen.client().chatStream(request, chunk -> {
					firstSent.set(true);
					consumer.accept(chunk);
				});
				long latencyMs = elapsedMs(start);
				this.latencyTracker.record(chosen.platform(), chosen.instanceId(), latencyMs);
				this.registry.markHealthy(chosen.platform(), chosen.instanceId());
				if (this.circuitBreakers != null) {
					this.circuitBreakers.onSuccess(chosen.platform(), chosen.instanceId());
				}
				if (this.metrics != null) {
					this.metrics.onRequestSuccess(ctx.method(), ctx.model(), latencyMs);
				}
				audit(AuditEvent.Status.SUCCESS, ctx, chosen, latencyMs, 0.0d, null);
				return;
			} catch (Exception ex) {
				if (firstSent.get()) {
					throw ex;
				}
				tried.add(chosen);
				suppressed.add(ex);
				lastCause = ex;
				long latencyMs = elapsedMs(start);
				if (this.circuitBreakers != null) {
					this.circuitBreakers.onFailure(chosen.platform(), chosen.instanceId());
				}
				if (this.metrics != null) {
					this.metrics.onRequestFailure(ctx.method(), ctx.model(), latencyMs);
				}
				if (!this.failoverConfig.isRetryable(ex)) {
					audit(AuditEvent.Status.FAILED, ctx, chosen, latencyMs, 0.0d, detailOf(ex));
					throw ex;
				}
				this.registry.markUnhealthy(chosen.platform(), chosen.instanceId(),
						this.failoverConfig.unhealthyCooldownMs());
				audit(AuditEvent.Status.FAILED_OVER, ctx, chosen, latencyMs, 0.0d, detailOf(ex));
				ClientCandidate next = nextCandidate(ctx);
				this.failoverConfig.listener().onFailover(
						chosen.platform(), chosen.instanceId(),
						next == null ? null : next.platform(),
						next == null ? null : next.instanceId(), ex, attempt);
			}
		}

		long totalMs = elapsedMs(totalStart);
		if (lastCause != null && lastAttempted != null) {
			audit(AuditEvent.Status.FAILED, ctx, lastAttempted, totalMs, 0.0d, detailOf(lastCause));
		}
		throw exhausted(tried, suppressed, lastCause, attempt);
	}

	/**
	 * 熔断过滤：装配了熔断器时剔除 OPEN 候选，被剔除者记入 tried / suppressed 并触发熔断拒绝指标。
	 *
	 * <p>被剔除的候选不调 action、不 markUnhealthy——它是被熔断器快速失败，而非本次调用失败。</p>
	 */
	private List<ClientCandidate> filterUsable(List<ClientCandidate> candidates,
			List<ClientCandidate> tried, List<Throwable> suppressed) {
		List<ClientCandidate> usable = new ArrayList<>();
		for (ClientCandidate c : candidates) {
			if (this.circuitBreakers != null
					&& !this.circuitBreakers.allowRequest(c.platform(), c.instanceId())) {
				tried.add(c);
				suppressed.add(new AiException(
						"circuit breaker OPEN for " + c.platform() + "/" + c.instanceId()));
				if (this.metrics != null) {
					this.metrics.onCircuitReject(c.platform(), c.instanceId());
				}
			} else {
				usable.add(c);
			}
		}
		return usable;
	}

	/** 失败后重新选择下一候选（不健康者已被注册表剔除）。 */
	private ClientCandidate nextCandidate(RequestContext ctx) {
		List<ClientCandidate> remaining = this.registry.healthyCandidates();
		if (remaining.isEmpty()) {
			return null;
		}
		return this.strategy.select(ctx, remaining);
	}

	/**
	 * 构建耗尽聚合异常；最后一次原因为熔断快速失败时使用含 {@code circuit breaker} 的快速失败消息。
	 */
	private AiException exhausted(List<ClientCandidate> tried, List<Throwable> suppressed,
			Exception lastCause, int attempt) {
		this.failoverConfig.listener().onExhausted(tried, lastCause);
		AiException exhausted;
		if (lastCause != null && lastCause.getMessage() != null
				&& lastCause.getMessage().contains("circuit breaker")) {
			exhausted = new AiException(
					"gateway fast-fail: all circuit breakers OPEN after " + attempt
							+ " attempt(s)", lastCause);
		} else {
			exhausted = new AiException("gateway exhausted after " + attempt + " attempt(s)",
					lastCause);
		}
		suppressed.forEach(exhausted::addSuppressed);
		return exhausted;
	}

	/**
	 * 估算单次成功调用成本：仅当装配了成本计算器、结果为 {@link ChatResponse} 且用量非空时计算；
	 * 模型名优先取响应回带模型，缺失时回退请求模型。流式路径恒为 0。
	 */
	private double estimateCost(RequestContext ctx, Object result) {
		if (this.costCalculator == null || !(result instanceof ChatResponse response)) {
			return 0.0d;
		}
		TokenUsage usage = response.usage();
		if (usage == null) {
			return 0.0d;
		}
		String model = response.model() != null ? response.model() : ctx.model();
		return this.costCalculator.calculate(model, usage);
	}

	/** 记录一条审计事件；未装配审计器时直接忽略。 */
	private void audit(AuditEvent.Status status, RequestContext ctx, ClientCandidate chosen,
			long durationMs, double cost, String detail) {
		if (this.auditLogger == null) {
			return;
		}
		this.auditLogger.log(new AuditEvent(
				String.valueOf(this.requestIds.incrementAndGet()),
				ctx.method(), ctx.model(), ctx.tenantId(),
				chosen == null ? null : chosen.platform(),
				chosen == null ? null : chosen.instanceId(),
				status, durationMs, cost, detail, System.currentTimeMillis()));
	}

	/** 取 suppressed 末尾项转为 Exception（全熔断快速失败时 suppressed 至少有一条熔断拒绝）。 */
	private static Exception lastSuppressed(List<Throwable> suppressed) {
		Throwable last = suppressed.get(suppressed.size() - 1);
		return last instanceof Exception e ? e : null;
	}

	/** 计算自起点以来的毫秒耗时。 */
	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	/** 审计 detail：异常类名 + 一句话消息。 */
	private static String detailOf(Exception ex) {
		String msg = ex.getMessage();
		return ex.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
	}
}
