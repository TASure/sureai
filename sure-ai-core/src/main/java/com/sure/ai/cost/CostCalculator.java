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

import com.sure.ai.cost.PriceCatalog.ModelPrice;
import com.sure.ai.model.TokenUsage;
import com.sure.tool.lang.Assert;

/**
 * 单次调用成本计算器。
 *
 * <p>基于 {@link PriceCatalog} 中各模型的 per-1K tokens 单价，把实际 token 用量折算为 USD 成本。
 * 本类无状态、线程安全。</p>
 *
 * <h2>缓存 token 处理</h2>
 * <p>由于 {@link TokenUsage} record 不含 cachedTokens 字段（不加组件以避免 Breaking Change），
 * 缓存读/写 token 数通过 {@link #calculate(String, TokenUsage, int, int)} 的额外参数传入。
 * 普通 {@link #calculate(String, TokenUsage)} 等价于 cachedRead=0、cachedWrite=0。</p>
 *
 * <p>计费公式：</p>
 * <pre>
 * billablePromptTokens = promptTokens - cachedReadTokens - cachedWriteTokens
 * cost = (billablePromptTokens * inputPer1k
 *       + cachedReadTokens   * cacheReadPer1k
 *       + cachedWriteTokens  * cacheWritePer1k
 *       + completionTokens   * outputPer1k) / 1000
 * </pre>
 *
 * <p>未收录模型返回 {@code 0.0}（网关路由场景下不抛异常，由调用方通过 {@link PriceCatalog#hasPrice} 预判）。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class CostCalculator {

	/** 中文估算系数：约 1.5 字符 = 1 token。 */
	private static final double CHINESE_CHARS_PER_TOKEN = 1.5;

	/** 英文估算系数：约 4 字符 = 1 token。 */
	private static final double LATIN_CHARS_PER_TOKEN = 4.0;

	private final PriceCatalog catalog;

	/**
	 * 构造。
	 *
	 * @param catalog 价格目录
	 */
	public CostCalculator(PriceCatalog catalog) {
		Assert.notNull(catalog, "catalog must not be null");
		this.catalog = catalog;
	}

	/**
	 * 按实际 TokenUsage 计算单次成本（无缓存 token）。
	 *
	 * @param model 模型名
	 * @param usage 实际 token 用量
	 * @return 成本（USD）；未收录模型返回 0.0
	 */
	public double calculate(String model, TokenUsage usage) {
		return calculate(model, usage, 0, 0);
	}

	/**
	 * 含缓存 token 的成本计算。
	 *
	 * <p>{@code cachedReadTokens} / {@code cachedWriteTokens} 必须是
	 * {@code usage.promptTokens()} 的子集（即已包含在 promptTokens 中）；
	 * 本方法会从 promptTokens 中扣除这两部分以避免重复计费。</p>
	 *
	 * @param model              模型名
	 * @param usage              实际 token 用量
	 * @param cachedReadTokens   缓存命中读 token 数（已含在 promptTokens 内）
	 * @param cachedWriteTokens  缓存写入 token 数（已含在 promptTokens 内）
	 * @return 成本（USD）；未收录模型返回 0.0
	 */
	public double calculate(String model, TokenUsage usage, int cachedReadTokens, int cachedWriteTokens) {
		Assert.notNull(usage, "usage must not be null");
		ModelPrice price = this.catalog.priceFor(model);
		if (price == null) {
			return 0.0;
		}
		int prompt = usage.promptTokens();
		int completion = usage.completionTokens();
		int billablePrompt = prompt - cachedReadTokens - cachedWriteTokens;
		if (billablePrompt < 0) {
			billablePrompt = 0;
		}
		double cost = billablePrompt * price.inputPer1k()
				+ cachedReadTokens * price.cacheReadPer1k()
				+ cachedWriteTokens * price.cacheWritePer1k()
				+ completion * price.outputPer1k();
		return cost / 1000.0;
	}

	/**
	 * 请求前粗估成本（按字符数估算 token）。
	 *
	 * <p>估算口径：中文字符约 1.5 字符/token，英文及其他字符约 4 字符/token。
	 * 此为粗略估算，仅用于请求前预算判断，不替代实际计费。</p>
	 *
	 * @param model                 模型名
	 * @param inputText             输入文本
	 * @param estimatedOutputTokens 预估输出 token 数
	 * @return 预估成本（USD）；未收录模型返回 0.0
	 */
	public double estimate(String model, String inputText, int estimatedOutputTokens) {
		Assert.notNull(inputText, "inputText must not be null");
		ModelPrice price = this.catalog.priceFor(model);
		if (price == null) {
			return 0.0;
		}
		int chineseChars = 0;
		int otherChars = 0;
		for (int i = 0; i < inputText.length(); i++) {
			char c = inputText.charAt(i);
			if (isChineseChar(c)) {
				chineseChars++;
			} else {
				otherChars++;
			}
		}
		double estimatedInputTokens = chineseChars / CHINESE_CHARS_PER_TOKEN
				+ otherChars / LATIN_CHARS_PER_TOKEN;
		double cost = estimatedInputTokens * price.inputPer1k()
				+ estimatedOutputTokens * price.outputPer1k();
		return cost / 1000.0;
	}

	/**
	 * 判断字符是否为中文（CJK 统一表意文字 + 扩展 A）。
	 *
	 * @param c 字符
	 * @return true=中文字符
	 */
	private static boolean isChineseChar(char c) {
		return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
	}
}
