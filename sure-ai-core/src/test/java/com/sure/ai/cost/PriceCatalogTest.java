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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.cost.PriceCatalog.ModelPrice;

/**
 * {@link PriceCatalog} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class PriceCatalogTest {

	/** 内置价格目录包含主流模型，价格 > 0。 */
	@Test
	public void testPriceCatalogDefaults() {
		PriceCatalog catalog = PriceCatalog.defaults();
		// 至少覆盖 OpenAI / Anthropic / Google / DeepSeek / Qwen / Mistral 六大厂商
		assertTrue("catalog should have gpt-4o", catalog.hasPrice("gpt-4o"));
		assertTrue("catalog should have gpt-4o-mini", catalog.hasPrice("gpt-4o-mini"));
		assertTrue("catalog should have gpt-4.1", catalog.hasPrice("gpt-4.1"));
		assertTrue("catalog should have o3", catalog.hasPrice("o3"));
		assertTrue("catalog should have o4-mini", catalog.hasPrice("o4-mini"));
		assertTrue("catalog should have claude-opus-4-7", catalog.hasPrice("claude-opus-4-7"));
		assertTrue("catalog should have claude-sonnet-4-6", catalog.hasPrice("claude-sonnet-4-6"));
		assertTrue("catalog should have claude-haiku-4-5", catalog.hasPrice("claude-haiku-4-5"));
		assertTrue("catalog should have gemini-2.5-pro", catalog.hasPrice("gemini-2.5-pro"));
		assertTrue("catalog should have gemini-2.5-flash", catalog.hasPrice("gemini-2.5-flash"));
		assertTrue("catalog should have deepseek-chat", catalog.hasPrice("deepseek-chat"));
		assertTrue("catalog should have deepseek-reasoner", catalog.hasPrice("deepseek-reasoner"));
		assertTrue("catalog should have qwen-plus", catalog.hasPrice("qwen-plus"));
		assertTrue("catalog should have qwen-max", catalog.hasPrice("qwen-max"));
		assertTrue("catalog should have qwen-turbo", catalog.hasPrice("qwen-turbo"));
		assertTrue("catalog should have mistral-large-latest", catalog.hasPrice("mistral-large-latest"));

		// 每个已收录模型的 input/output 价格必须 > 0
		assertTrue(catalog.size() >= 15);
		ModelPrice gpt4o = catalog.priceFor("gpt-4o");
		assertNotNull(gpt4o);
		assertTrue(gpt4o.inputPer1k() > 0);
		assertTrue(gpt4o.outputPer1k() > 0);
		assertEquals("USD", gpt4o.currency());
	}

	/** withPrice 覆盖后 priceFor 返回新价格。 */
	@Test
	public void testPriceCatalogOverride() {
		PriceCatalog catalog = PriceCatalog.defaults();
		ModelPrice original = catalog.priceFor("gpt-4o");
		assertNotNull(original);
		double originalInput = original.inputPer1k();

		PriceCatalog overridden = catalog.withPrice("gpt-4o",
				new ModelPrice(0.99, 1.99, 0.10, 0.20));
		// 原 catalog 不变
		assertEquals(originalInput, catalog.priceFor("gpt-4o").inputPer1k(), 0.0);
		// 新 catalog 返回覆盖后价格
		assertEquals(0.99, overridden.priceFor("gpt-4o").inputPer1k(), 0.0);
		assertEquals(1.99, overridden.priceFor("gpt-4o").outputPer1k(), 0.0);
		assertEquals(0.10, overridden.priceFor("gpt-4o").cacheReadPer1k(), 0.0);
		assertEquals(0.20, overridden.priceFor("gpt-4o").cacheWritePer1k(), 0.0);
		// 其他模型不受影响
		assertEquals(catalog.priceFor("o3").inputPer1k(),
				overridden.priceFor("o3").inputPer1k(), 0.0);
	}

	/** 未收录模型 priceFor 返回 null，hasPrice 返回 false。 */
	@Test
	public void testPriceCatalogUnknownModel() {
		PriceCatalog catalog = PriceCatalog.defaults();
		assertFalse(catalog.hasPrice("nonexistent-model-xyz"));
		assertNull(catalog.priceFor("nonexistent-model-xyz"));
		assertNull(catalog.priceFor(null));
	}

	/** Anthropic 模型有缓存写价（5m TTL 写入费 = input × 1.25）。 */
	@Test
	public void testAnthropicCacheWritePrice() {
		PriceCatalog catalog = PriceCatalog.defaults();
		ModelPrice sonnet = catalog.priceFor("claude-sonnet-4-6");
		assertNotNull(sonnet);
		// cacheWritePer1k = inputPer1k * 1.25 = 0.003 * 1.25 = 0.00375
		assertEquals(0.00375, sonnet.cacheWritePer1k(), 1e-10);
		// cacheReadPer1k = inputPer1k * 0.1 = 0.0003
		assertEquals(0.0003, sonnet.cacheReadPer1k(), 1e-10);
	}

	/** OpenAI 模型无单独缓存写费（cacheWritePer1k = 0），缓存读为折扣输入价。 */
	@Test
	public void testOpenAiCacheReadDiscount() {
		PriceCatalog catalog = PriceCatalog.defaults();
		ModelPrice gpt4o = catalog.priceFor("gpt-4o");
		assertNotNull(gpt4o);
		// cached input = 50% of input
		assertEquals(gpt4o.inputPer1k() / 2.0, gpt4o.cacheReadPer1k(), 1e-10);
		// no separate cache write surcharge
		assertEquals(0.0, gpt4o.cacheWritePer1k(), 0.0);
	}
}
