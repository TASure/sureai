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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.sure.ai.model.TokenUsage;

/**
 * {@link CostCalculator} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class CostCalculatorTest {

	private final PriceCatalog catalog = PriceCatalog.defaults();
	private final CostCalculator calculator = new CostCalculator(catalog);

	/** 已知模型 + TokenUsage：成本 = prompt×input价 + completion×output价（逐数值断言）。 */
	@Test
	public void testCalculateBasic() {
		// gpt-4o: inputPer1k=0.005, outputPer1k=0.020
		// prompt=1000, completion=500
		// cost = (1000*0.005 + 500*0.020) / 1000 = (5.0 + 10.0) / 1000 = 0.015
		TokenUsage usage = TokenUsage.of(1000, 500, 1500);
		double cost = calculator.calculate("gpt-4o", usage);
		assertEquals(0.015, cost, 0.0001);
	}

	/** 含缓存读/写 token 的成本计算正确。 */
	@Test
	public void testCalculateWithCache() {
		// claude-sonnet-4-6: inputPer1k=0.003, outputPer1k=0.015,
		//   cacheReadPer1k=0.0003, cacheWritePer1k=0.00375
		// prompt=1000, completion=200, cachedRead=400, cachedWrite=100
		// billablePrompt = 1000-400-100 = 500
		// cost = (500*0.003 + 400*0.0003 + 100*0.00375 + 200*0.015) / 1000
		//      = (1.5 + 0.12 + 0.375 + 3.0) / 1000 = 4.995 / 1000 = 0.004995
		TokenUsage usage = TokenUsage.of(1000, 200, 1200);
		double cost = calculator.calculate("claude-sonnet-4-6", usage, 400, 100);
		assertEquals(0.004995, cost, 0.0001);
	}

	/** 未知模型返回 0.0。 */
	@Test
	public void testCalculateUnknownModel() {
		TokenUsage usage = TokenUsage.of(1000, 500, 1500);
		double cost = calculator.calculate("mystery-model-xyz", usage);
		assertEquals(0.0, cost, 0.0);
	}

	/** 粗估成本按字符数×token估算系数计算（纯英文）。 */
	@Test
	public void testEstimateByCharactersEnglish() {
		// gpt-4o: inputPer1k=0.005, outputPer1k=0.020
		// "Hello world" = 11 ASCII chars → 11/4.0 = 2.75 input tokens
		// estimatedOutputTokens = 100
		// cost = (2.75*0.005 + 100*0.020) / 1000 = (0.01375 + 2.0) / 1000 = 0.00201375
		double cost = calculator.estimate("gpt-4o", "Hello world", 100);
		assertEquals(0.00201375, cost, 0.0000001);
	}

	/** 粗估成本按字符数×token估算系数计算（纯中文）。 */
	@Test
	public void testEstimateByCharactersChinese() {
		// gpt-4o: inputPer1k=0.005, outputPer1k=0.020
		// "你好世界" = 4 Chinese chars → 4/1.5 = 2.6667 input tokens
		// estimatedOutputTokens = 0
		// cost = (2.6667*0.005 + 0) / 1000 = 0.013333/1000 = 0.000013333
		double cost = calculator.estimate("gpt-4o", "你好世界", 0);
		assertEquals(4.0 / 1.5 * 0.005 / 1000.0, cost, 1e-10);
	}

	/** 粗估：未知模型返回 0。 */
	@Test
	public void testEstimateUnknownModel() {
		double cost = calculator.estimate("mystery", "Hello", 10);
		assertEquals(0.0, cost, 0.0);
	}

	/** 粗估：空文本。 */
	@Test
	public void testEstimateEmptyText() {
		double cost = calculator.estimate("gpt-4o", "", 0);
		assertEquals(0.0, cost, 0.0);
	}

	/** calculate 无缓存参数等价于 cachedRead=0, cachedWrite=0。 */
	@Test
	public void testCalculateNoCacheEquivalent() {
		TokenUsage usage = TokenUsage.of(1000, 500, 1500);
		double noCache = calculator.calculate("gpt-4o", usage);
		double explicit = calculator.calculate("gpt-4o", usage, 0, 0);
		assertEquals(noCache, explicit, 0.0);
	}

	/** DeepSeek 价格手算验证：deepseek-chat input=0.00028, output=0.00042 per 1K。 */
	@Test
	public void testDeepSeekPricing() {
		// prompt=1000, completion=1000
		// cost = (1000*0.00028 + 1000*0.00042) / 1000 = (0.28 + 0.42) / 1000 = 0.0007
		TokenUsage usage = TokenUsage.of(1000, 1000, 2000);
		double cost = calculator.calculate("deepseek-chat", usage);
		assertEquals(0.0007, cost, 0.00001);
	}
}
