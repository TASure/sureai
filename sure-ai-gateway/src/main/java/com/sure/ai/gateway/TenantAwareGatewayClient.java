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

import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.TokenUsage;

/**
 * 租户感知网关客户端：装饰一个 {@link AiClient}（通常是 {@link GatewayClient}），在每次调用前后
 * 接入 {@link BudgetEnforcer} 的预算 / 配额管控。
 *
 * <p>租户 ID 从 {@link ChatRequest#extra()} 的 {@link RequestContext#EXTRA_TENANT_ID} 键读取
 * （与 {@link GatewayClient} 构建 {@link RequestContext} 的口径一致）。调用方负责先用
 * {@link TenantManager#resolveTenant(String)} 把虚拟密钥解析成租户 ID 再放入 extra。</p>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li>取到 tenantId 后，调用前 {@link BudgetEnforcer#checkBeforeCall} 校验成本/token/QPS，
 *       超限直接抛 {@link com.sure.ai.exception.AiBudgetExceededException}，不触达下游；</li>
 *   <li>调用下游 {@code delegate.chat}；</li>
 *   <li>成功后用可选的 {@link CostCalculator} 把实际 {@link TokenUsage} 折算为 USD 成本，
 *       {@link BudgetEnforcer#recordAfterCall} 入账（同时写入共享 {@code CostAggregator}）。</li>
 * </ol>
 *
 * <p>未带 tenantId 的请求直接透传，不做任何管控。流式调用因分片不带累计用量，只做事前检查、
 * 不做事后计量（本迭代限制）。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class TenantAwareGatewayClient implements AiClient {

	/** 被装饰的下游客户端（通常是 {@link GatewayClient}）。 */
	private final AiClient delegate;

	/** 预算执行器。 */
	private final BudgetEnforcer enforcer;

	/** 可选成本计算器；为 null 时事后成本记 0（仍记录 token 数）。 */
	private final CostCalculator costCalculator;

	/**
	 * 构造器（不做事后成本折算，只记 token）。
	 *
	 * @param delegate  下游客户端
	 * @param enforcer  预算执行器
	 */
	public TenantAwareGatewayClient(AiClient delegate, BudgetEnforcer enforcer) {
		this(delegate, enforcer, null);
	}

	/**
	 * 全参构造器。
	 *
	 * @param delegate       下游客户端
	 * @param enforcer       预算执行器
	 * @param costCalculator 成本计算器，可为 null
	 */
	public TenantAwareGatewayClient(AiClient delegate, BudgetEnforcer enforcer,
			CostCalculator costCalculator) {
		this.delegate = delegate;
		this.enforcer = enforcer;
		this.costCalculator = costCalculator;
	}

	@Override
	public String name() {
		return "tenant-aware";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String tenantId = tenantIdOf(request);
		if (tenantId != null) {
			this.enforcer.checkBeforeCall(tenantId, 0.0d);
		}
		ChatResponse response = this.delegate.chat(request);
		if (tenantId != null) {
			String model = response.model() != null ? response.model() : request.model();
			TokenUsage usage = response.usage();
			double cost = this.costCalculator == null || usage == null
					? 0.0d : this.costCalculator.calculate(model, usage);
			this.enforcer.recordAfterCall(tenantId, model, usage, cost);
		}
		return response;
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		String tenantId = tenantIdOf(request);
		if (tenantId != null) {
			this.enforcer.checkBeforeCall(tenantId, 0.0d);
		}
		this.delegate.chatStream(request, consumer);
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	/**
	 * 从请求 extra 解析租户 ID。
	 *
	 * @param request 请求
	 * @return 租户 ID，或 null
	 */
	private static String tenantIdOf(ChatRequest request) {
		Object t = request.extra().get(RequestContext.EXTRA_TENANT_ID);
		return t instanceof String s && !s.isBlank() ? s : null;
	}
}
