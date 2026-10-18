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

package com.sure.ai.agent.tool.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.sure.ai.model.ToolFunction;

/**
 * {@link ToolCatalog} 发现能力：标签 / 关键词 / 来源 / 风险级别过滤与清单快照。
 */
public class ToolCatalogDiscoveryTest {

	private static ToolFunction fn(String name) {
		return ToolFunction.of(name, "desc-" + name, "{\"type\":\"object\"}");
	}

	private static ToolMetadata meta(String name, RiskLevel level, ToolSource source, String... tags) {
		ToolMetadata.Builder b = ToolMetadata.builder(name)
			.description("meta-" + name)
			.riskLevel(level)
			.source(source);
		for (String t : tags) {
			b.addTag(t);
		}
		return b.build();
	}

	private ToolCatalog seeded() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.register(fn("weather"), args -> "w", meta("weather", RiskLevel.LOW, ToolSource.LOCAL, "weather", "query"));
		catalog.register(fn("search_web"), args -> "s", meta("search_web", RiskLevel.MEDIUM, ToolSource.MCP, "search", "web"));
		catalog.register(fn("delete_file"), args -> "d", meta("delete_file", RiskLevel.HIGH, ToolSource.LOCAL, "fs", "danger"));
		return catalog;
	}

	@Test
	public void testFindByTagCaseInsensitive() {
		ToolCatalog catalog = seeded();
		List<ToolEntry> hit = catalog.findByTag("QUERY");
		assertEquals(1, hit.size());
		assertEquals("weather", hit.get(0).name());
		assertTrue(catalog.findByTag("missing").isEmpty());
		assertTrue(catalog.findByTag(null).isEmpty());
		assertTrue(catalog.findByTag("   ").isEmpty());
	}

	@Test
	public void testFindByKeywordMatchesNameDescriptionAndTag() {
		ToolCatalog catalog = seeded();
		assertEquals(1, catalog.findByKeyword("weather").size());
		// 命中函数描述
		assertEquals(1, catalog.findByKeyword("desc-search_web").size());
		// 命中元数据描述
		assertEquals(1, catalog.findByKeyword("meta-delete_file").size());
		// 仅命中标签（danger 不出现在名 / 描述中）
		List<ToolEntry> danger = catalog.findByKeyword("danger");
		assertEquals(1, danger.size());
		assertEquals("delete_file", danger.get(0).name());
		assertTrue(catalog.findByKeyword("nope").isEmpty());
		assertTrue(catalog.findByKeyword(null).isEmpty());
		assertTrue(catalog.findByKeyword("  ").isEmpty());
	}

	@Test
	public void testFilterBySource() {
		ToolCatalog catalog = seeded();
		assertEquals(2, catalog.filterBySource(ToolSource.LOCAL).size());
		assertEquals(1, catalog.filterBySource(ToolSource.MCP).size());
		assertTrue(catalog.filterBySource(null).isEmpty());
	}

	@Test
	public void testFilterByRiskLevel() {
		ToolCatalog catalog = seeded();
		assertEquals(1, catalog.filterByRiskLevel(RiskLevel.HIGH).size());
		assertEquals(1, catalog.filterByRiskLevel(RiskLevel.MEDIUM).size());
		assertEquals(1, catalog.filterByRiskLevel(RiskLevel.LOW).size());
		assertTrue(catalog.filterByRiskLevel(null).isEmpty());
	}

	@Test
	public void testGetSizeIsEmptyUnregisterClear() {
		ToolCatalog catalog = seeded();
		assertEquals(3, catalog.size());
		Optional<ToolEntry> e = catalog.get("weather");
		assertTrue(e.isPresent());
		assertTrue(catalog.get("absent").isEmpty());
		assertTrue(catalog.get(null).isEmpty());
		assertTrue(catalog.unregister("weather"));
		assertEquals(2, catalog.size());
		assertTrue(!catalog.isEmpty());
		// null 与未知名注销均返回 false
		assertTrue(!catalog.unregister(null));
		assertTrue(!catalog.unregister("ghost"));
		catalog.clear();
		assertTrue(catalog.isEmpty());
		assertTrue(!catalog.unregister("weather"));
	}

	@Test
	public void testListSortedByName() {
		ToolCatalog catalog = seeded();
		List<ToolEntry> list = catalog.list();
		assertEquals(3, list.size());
		assertEquals("delete_file", list.get(0).name());
		assertEquals("search_web", list.get(1).name());
		assertEquals("weather", list.get(2).name());
	}

	@Test
	public void testDefaultMetadataOnPlainRegister() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.register(fn("plain"), args -> "ok");
		ToolEntry e = catalog.get("plain").orElseThrow();
		assertEquals(RiskLevel.LOW, e.metadata().riskLevel());
		assertEquals(ToolSource.LOCAL, e.metadata().source());
		assertEquals("desc-plain", e.function().description());
		// spec() 包装出 ToolSpec 供 ChatRequest.tools
		assertEquals("plain", e.spec().function().name());
	}

	@Test
	public void testNullFunctionDescriptionBecomesEmpty() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.register(ToolFunction.of("nodesc", null, "{}"), args -> "ok");
		ToolEntry e = catalog.get("nodesc").orElseThrow();
		assertEquals("", e.metadata().description());
	}
}
