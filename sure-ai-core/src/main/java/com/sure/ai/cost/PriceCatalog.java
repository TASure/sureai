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

import java.util.LinkedHashMap;
import java.util.Map;

import com.sure.tool.lang.Assert;

/**
 * 模型价格目录：维护各模型的 input/output/cacheRead/cacheWrite 单价（每 1K tokens，USD）。
 *
 * <p>价格单位统一为 <b>每 1K tokens（USD）</b>，与厂商公布的 per-1M tokens 价格除以 1000 一致。
 * 本类不可变；{@link #withPrice(String, ModelPrice)} 返回新实例以支持自定义/覆盖。</p>
 *
 * <h2>内置价格数据来源与核实日期</h2>
 * <p>以下内置价格均在代码注释中标注来源 URL 与核实日期。无法核实的模型不收录。
 * 厂商调价后可通过 {@link #withPrice(String, ModelPrice)} 覆盖。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class PriceCatalog {

	/**
	 * 单个模型的单价。
	 *
	 * @param inputPer1k      普通输入 token 单价（USD / 1K tokens）
	 * @param outputPer1k     输出 token 单价（USD / 1K tokens）
	 * @param cacheReadPer1k  缓存命中读 token 单价（USD / 1K tokens）；无公开缓存定价时为 0
	 * @param cacheWritePer1k 缓存写入 token 单价（USD / 1K tokens）；OpenAI/Gemini 无单独缓存写费（缓存读即折扣输入），
	 *                        Anthropic 缓存写为 5m TTL 写入价（input 的 1.25 倍）；无公开定价时为 0
	 * @param currency        币种（当前统一 USD）
	 */
	public record ModelPrice(double inputPer1k, double outputPer1k,
			double cacheReadPer1k, double cacheWritePer1k, String currency) {

		/**
		 * 简化构造（USD）。
		 *
		 * @param inputPer1k      输入单价
		 * @param outputPer1k     输出单价
		 * @param cacheReadPer1k  缓存读单价
		 * @param cacheWritePer1k 缓存写单价
		 */
		public ModelPrice(double inputPer1k, double outputPer1k,
				double cacheReadPer1k, double cacheWritePer1k) {
			this(inputPer1k, outputPer1k, cacheReadPer1k, cacheWritePer1k, "USD");
		}
	}

	/** 不可变价格映射（model → price）。 */
	private final Map<String, ModelPrice> prices;

	private PriceCatalog(Map<String, ModelPrice> prices) {
		this.prices = prices;
	}

	/**
	 * 内置主流模型价格目录。
	 *
	 * <p>价格均为 USD / 1K tokens。数据来源见各模型注释。核实日期：2026-09-26。</p>
	 *
	 * @return 内置价格目录
	 */
	public static PriceCatalog defaults() {
		Map<String, ModelPrice> m = new LinkedHashMap<>();

		// ===== OpenAI =====
		// gpt-4o: $5.00 input / $2.50 cached / $20.00 output per 1M
		// Source: https://openai.com/api/pricing/  (verified 2026-09-26)
		m.put("gpt-4o", new ModelPrice(0.005, 0.020, 0.0025, 0.0));

		// gpt-4o-mini: $0.15 input / $0.075 cached / $0.60 output per 1M
		// Source: https://openai.com/api/pricing/  (long-standing launch pricing, verified 2026-09-26)
		m.put("gpt-4o-mini", new ModelPrice(0.00015, 0.0006, 0.000075, 0.0));

		// gpt-4.1: $2.00 input / $0.50 cached / $8.00 output per 1M
		// Source: OpenAI GPT-4.1 launch blog, 2025-04-14
		//         https://openai.com/index/introducing-gpt-4-1-in-the-api/  (verified 2026-09-26)
		m.put("gpt-4.1", new ModelPrice(0.002, 0.008, 0.0005, 0.0));

		// o3: $10.00 input / $2.50 cached / $40.00 output per 1M
		// Source: OpenAI o3 & o4-mini release announcement, 2025-04-16
		m.put("o3", new ModelPrice(0.010, 0.040, 0.0025, 0.0));

		// o4-mini: $1.10 input / $0.275 cached / $4.40 output per 1M
		// Source: OpenAI o3 & o4-mini release announcement, 2025-04-16;
		//         confirmed by Azure OpenAI pricing page (verified 2026-09-26)
		m.put("o4-mini", new ModelPrice(0.0011, 0.0044, 0.000275, 0.0));

		// ===== Anthropic Claude =====
		// Claude Opus 4 系: $15 input / $1.50 cache read / $18.75 cache write(5m) / $75 output per 1M
		// Source: https://docs.anthropic.com/en/docs/about-claude/models/overview
		//         https://platform.claude.com/docs/en/about-claude/pricing  (verified 2026-09-26)
		m.put("claude-opus-4-7", new ModelPrice(0.015, 0.075, 0.0015, 0.01875));
		m.put("claude-opus-4-6", new ModelPrice(0.015, 0.075, 0.0015, 0.01875));

		// Claude Sonnet 4 系: $3 input / $0.30 cache read / $3.75 cache write(5m) / $15 output per 1M
		// Source: same as above (verified 2026-09-26)
		m.put("claude-sonnet-4-6", new ModelPrice(0.003, 0.015, 0.0003, 0.00375));
		m.put("claude-sonnet-4-5", new ModelPrice(0.003, 0.015, 0.0003, 0.00375));

		// Claude Haiku 4.5: $1 input / $0.10 cache read / $1.25 cache write(5m) / $5 output per 1M
		// Source: https://platform.claude.com/docs/en/about-claude/pricing  (verified 2026-09-26)
		m.put("claude-haiku-4-5", new ModelPrice(0.001, 0.005, 0.0001, 0.00125));

		// ===== Google Gemini =====
		// Gemini 2.5 Pro (≤200K context): $1.25 input / $0.3125 cache read / $10.00 output per 1M
		// Source: https://ai.google.dev/gemini-api/docs/pricing
		//         https://cloud.google.com/vertex-ai/generative-ai/pricing  (verified 2026-09-26)
		m.put("gemini-2.5-pro", new ModelPrice(0.00125, 0.010, 0.0003125, 0.0));

		// Gemini 2.5 Flash: $0.30 input / $0.03 cache read / $2.50 output per 1M
		// Source: same as above (verified 2026-09-26)
		m.put("gemini-2.5-flash", new ModelPrice(0.0003, 0.0025, 0.00003, 0.0));

		// ===== DeepSeek =====
		// deepseek-chat / deepseek-reasoner: $0.28 input(cache miss) / $0.028 cache hit / $0.42 output per 1M
		// Source: https://api-docs.deepseek.com/quick_start/pricing
		//         cross-checked: https://metronome.com/pricing-index/deepseek  (verified 2026-09-26)
		m.put("deepseek-chat", new ModelPrice(0.00028, 0.00042, 0.000028, 0.0));
		m.put("deepseek-reasoner", new ModelPrice(0.00028, 0.00042, 0.000028, 0.0));

		// ===== Alibaba Qwen (international USD pricing) =====
		// qwen-turbo: $0.40 input / $1.20 output per 1M
		// qwen-plus:  $3.00 input / $9.00 output per 1M
		// qwen-max:   $10.00 input / $30.00 output per 1M
		// Source: https://www.alibabacloud.com/help/en/model-studio/developer-reference/billing-for-tongyiqianwen
		//         (verified 2026-09-26)
		m.put("qwen-turbo", new ModelPrice(0.0004, 0.0012, 0.0, 0.0));
		m.put("qwen-plus", new ModelPrice(0.003, 0.009, 0.0, 0.0));
		m.put("qwen-max", new ModelPrice(0.010, 0.030, 0.0, 0.0));

		// ===== Mistral =====
		// mistral-large-latest (Mistral Large 3): $0.50 input / $1.50 output per 1M
		// Source: https://mistral.ai/technology/#pricing (Dec 2025 release)
		//         cross-checked: https://openrouter.ai/mistralai/mistral-large-2512  (verified 2026-09-26)
		m.put("mistral-large-latest", new ModelPrice(0.0005, 0.0015, 0.0, 0.0));

		return new PriceCatalog(Map.copyOf(m));
	}

	/**
	 * 返回覆盖/新增后的新目录（本实例不变）。
	 *
	 * @param model 模型名
	 * @param price 单价
	 * @return 新目录
	 */
	public PriceCatalog withPrice(String model, ModelPrice price) {
		Assert.notBlank(model, "model must not be blank");
		Assert.notNull(price, "price must not be null");
		Map<String, ModelPrice> copy = new LinkedHashMap<>(this.prices);
		copy.put(model, price);
		return new PriceCatalog(Map.copyOf(copy));
	}

	/**
	 * 查询模型单价；未收录返回 {@code null}。
	 *
	 * @param model 模型名
	 * @return 单价，或 null
	 */
	public ModelPrice priceFor(String model) {
		if (model == null) {
			return null;
		}
		return this.prices.get(model);
	}

	/**
	 * 是否收录该模型。
	 *
	 * @param model 模型名
	 * @return true=已收录
	 */
	public boolean hasPrice(String model) {
		return model != null && this.prices.containsKey(model);
	}

	/**
	 * 已收录模型数。
	 *
	 * @return 模型数
	 */
	public int size() {
		return this.prices.size();
	}
}
