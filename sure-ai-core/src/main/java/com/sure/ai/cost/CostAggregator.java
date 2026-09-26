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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

import com.sure.ai.model.TokenUsage;
import com.sure.tool.lang.Assert;

/**
 * 成本汇总统计（纯 JDK 内存实现，线程安全）。
 *
 * <p>支持按租户、按模型、按时间窗聚合。内部使用 {@link ConcurrentHashMap} +
 * {@link LongAdder}/{@link DoubleAdder} 保证并发 record 不丢数据。
 * 时间窗查询（{@link #summarySince(long)}）通过遍历存储的记录列表过滤时间戳实现；
 * 调用 {@link #reset()} 可清空全部数据。</p>
 *
 * <p>数据结构：</p>
 * <ul>
 *   <li>{@code tenantTotals}：tenant → 跨模型累计（O(1) 查询）</li>
 *   <li>{@code tenantModelTotals}：tenant → model → 累计（O(1) 查询）</li>
 *   <li>{@code modelTotals}：model → 跨租户累计（O(1) 查询）</li>
 *   <li>{@code records}：每次调用的完整记录（用于时间窗过滤）</li>
 * </ul>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class CostAggregator {

	/** 可变累计器（线程安全，基于 LongAdder/DoubleAdder）。 */
	private static final class Totals {
		final LongAdder calls = new LongAdder();
		final LongAdder promptTokens = new LongAdder();
		final LongAdder completionTokens = new LongAdder();
		final DoubleAdder cost = new DoubleAdder();

		void record(long prompt, long completion, double c) {
			this.calls.increment();
			this.promptTokens.add(prompt);
			this.completionTokens.add(completion);
			this.cost.add(c);
		}
	}

	/** 单次调用记录（用于时间窗过滤）。 */
	private record CostRecord(long timestamp, String tenantId, String model,
			long promptTokens, long completionTokens, double cost) {
	}

	private final ConcurrentHashMap<String, Totals> tenantTotals = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, ConcurrentHashMap<String, Totals>> tenantModelTotals = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Totals> modelTotals = new ConcurrentHashMap<>();
	private final ConcurrentLinkedDeque<CostRecord> records = new ConcurrentLinkedDeque<>();

	/**
	 * 记录一次调用成本。
	 *
	 * @param tenantId 租户 ID
	 * @param model    模型名
	 * @param usage    token 用量
	 * @param cost     本次成本（USD）
	 */
	public void record(String tenantId, String model, TokenUsage usage, double cost) {
		Assert.notBlank(tenantId, "tenantId must not be blank");
		Assert.notBlank(model, "model must not be blank");
		Assert.notNull(usage, "usage must not be null");
		long prompt = usage.promptTokens();
		long completion = usage.completionTokens();

		tenantTotals.computeIfAbsent(tenantId, k -> new Totals()).record(prompt, completion, cost);
		tenantModelTotals.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(model, k -> new Totals())
			.record(prompt, completion, cost);
		modelTotals.computeIfAbsent(model, k -> new Totals()).record(prompt, completion, cost);
		records.add(new CostRecord(System.currentTimeMillis(), tenantId, model, prompt, completion, cost));
	}

	/**
	 * 按租户查询全量汇总。
	 *
	 * @param tenantId 租户 ID
	 * @return 汇总快照
	 */
	public CostSummary tenantSummary(String tenantId) {
		Assert.notBlank(tenantId, "tenantId must not be blank");
		Totals t = tenantTotals.get(tenantId);
		if (t == null) {
			return CostSummary.EMPTY;
		}
		Map<String, Totals> byModel = tenantModelTotals.get(tenantId);
		Map<String, Long> tokensByModel = new HashMap<>();
		Map<String, Double> costByModel = new HashMap<>();
		if (byModel != null) {
			byModel.forEach((model, mt) -> {
				tokensByModel.put(model, mt.promptTokens.sum() + mt.completionTokens.sum());
				costByModel.put(model, mt.cost.sum());
			});
		}
		return new CostSummary(t.calls.sum(), t.promptTokens.sum(), t.completionTokens.sum(),
				t.cost.sum(), Map.copyOf(tokensByModel), Map.copyOf(costByModel));
	}

	/**
	 * 按模型查询全量汇总（跨所有租户）。
	 *
	 * @param model 模型名
	 * @return 汇总快照
	 */
	public CostSummary modelSummary(String model) {
		Assert.notBlank(model, "model must not be blank");
		Totals t = modelTotals.get(model);
		if (t == null) {
			return CostSummary.EMPTY;
		}
		long tokens = t.promptTokens.sum() + t.completionTokens.sum();
		return new CostSummary(t.calls.sum(), t.promptTokens.sum(), t.completionTokens.sum(),
				t.cost.sum(), Map.of(model, tokens), Map.of(model, t.cost.sum()));
	}

	/**
	 * 按时间窗查询汇总（自 {@code epochMillis} 起，含边界；跨所有租户和模型）。
	 *
	 * @param epochMillis 起始时间戳（毫秒）
	 * @return 汇总快照
	 */
	public CostSummary summarySince(long epochMillis) {
		long calls = 0;
		long prompt = 0;
		long completion = 0;
		double cost = 0.0;
		Map<String, Long> tokensByModel = new HashMap<>();
		Map<String, Double> costByModel = new HashMap<>();
		for (CostRecord r : records) {
			if (r.timestamp() < epochMillis) {
				continue;
			}
			calls++;
			prompt += r.promptTokens();
			completion += r.completionTokens();
			cost += r.cost();
			long tokens = r.promptTokens() + r.completionTokens();
			tokensByModel.merge(r.model(), tokens, Long::sum);
			costByModel.merge(r.model(), r.cost(), Double::sum);
		}
		return new CostSummary(calls, prompt, completion, cost,
				Map.copyOf(tokensByModel), Map.copyOf(costByModel));
	}

	/**
	 * 重置全部汇总数据。
	 */
	public void reset() {
		tenantTotals.clear();
		tenantModelTotals.clear();
		modelTotals.clear();
		records.clear();
	}
}
