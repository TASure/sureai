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
import java.util.function.Consumer;
import java.util.function.Function;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

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
 *   <li>从注册表取当前<b>健康</b>候选；</li>
 *   <li>路由策略选出一个候选并调用；</li>
 *   <li>成功：记录延迟、恢复健康，返回结果；</li>
 *   <li>失败：若异常可转移（超时/5xx/连接错误），摘除该实例并切下一候选重试；
 *       若不可转移（4xx），直接抛出；达到 maxAttempts 或无健康候选则抛聚合异常。</li>
 * </ol>
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

	/**
	 * 全量构造器。
	 *
	 * @param registry       注册中心
	 * @param strategy       路由策略
	 * @param failoverConfig 故障转移配置
	 */
	public GatewayClient(ClientRegistry registry, RoutingStrategy strategy,
			FailoverConfig failoverConfig) {
		this.registry = registry;
		this.strategy = strategy == null
				? new ExplicitRoutingStrategy(new CapabilityRoutingStrategy(new RoundRobinStrategy()))
				: strategy;
		this.failoverConfig = failoverConfig == null ? FailoverConfig.defaults() : failoverConfig;
	}

	/**
	 * 便捷构造器：默认组合策略（显式平台 → 能力过滤 → 轮询）+ 默认故障转移配置。
	 *
	 * @param registry 注册中心
	 */
	public GatewayClient(ClientRegistry registry) {
		this(registry, null, null);
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
		execute(contextOf(request), client -> {
			client.chatStream(request, consumer);
			return null;
		});
	}

	@Override
	public void close() {
		this.registry.all().forEach(AiClient::close);
	}

	/** 从请求构建路由上下文。 */
	private RequestContext contextOf(ChatRequest request) {
		Capability cap = request.stream() ? Capability.CHAT_STREAM : Capability.CHAT;
		Object tenant = request.extra().get(RequestContext.EXTRA_TENANT_ID);
		return new RequestContext("chat", cap, request.model(), request.extra(),
				tenant instanceof String s ? s : null);
	}

	/** 故障转移执行模板。 */
	private <T> T execute(RequestContext ctx, Function<AiClient, T> action) {
		List<ClientCandidate> tried = new ArrayList<>();
		List<Throwable> suppressed = new ArrayList<>();
		Exception lastCause = null;
		int attempt = 0;

		while (attempt < this.failoverConfig.maxAttempts()) {
			List<ClientCandidate> candidates = this.registry.healthyCandidates();
			if (candidates.isEmpty()) {
				break;
			}
			ClientCandidate chosen = this.strategy.select(ctx, candidates);
			if (chosen == null) {
				break;
			}
			attempt++;
			long start = System.nanoTime();
			try {
				T result = action.apply(chosen.client());
				long latencyMs = (System.nanoTime() - start) / 1_000_000L;
				this.latencyTracker.record(chosen.platform(), chosen.instanceId(), latencyMs);
				this.registry.markHealthy(chosen.platform(), chosen.instanceId());
				return result;
			} catch (Exception ex) {
				tried.add(chosen);
				suppressed.add(ex);
				lastCause = ex;
				if (!this.failoverConfig.isRetryable(ex)) {
					throw ex;
				}
				this.registry.markUnhealthy(chosen.platform(), chosen.instanceId(),
						this.failoverConfig.unhealthyCooldownMs());
				ClientCandidate next = nextCandidate(ctx);
				this.failoverConfig.listener().onFailover(
						chosen.platform(), chosen.instanceId(),
						next == null ? null : next.platform(),
						next == null ? null : next.instanceId(), ex, attempt);
			}
		}

		this.failoverConfig.listener().onExhausted(tried, lastCause);
		AiException exhausted = new AiException(
				"gateway exhausted after " + attempt + " attempt(s)", lastCause);
		suppressed.forEach(exhausted::addSuppressed);
		throw exhausted;
	}

	/** 失败后重新选择下一候选（不健康者已被注册表剔除）。 */
	private ClientCandidate nextCandidate(RequestContext ctx) {
		List<ClientCandidate> remaining = this.registry.healthyCandidates();
		if (remaining.isEmpty()) {
			return null;
		}
		return this.strategy.select(ctx, remaining);
	}
}
