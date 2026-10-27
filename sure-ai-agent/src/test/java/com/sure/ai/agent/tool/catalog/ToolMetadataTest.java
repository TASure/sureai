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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * {@link ToolMetadata} 构造校验、标签归一化与默认值测试。
 */
public class ToolMetadataTest {

	@Test
	public void testDefaultsAndGetters() {
		ToolMetadata meta = ToolMetadata.builder("t1")
			.description("desc")
			.version("1.2.3")
			.docUrl("https://example.com/doc")
			.build();
		assertEquals("t1", meta.name());
		assertEquals("desc", meta.description());
		assertEquals("1.2.3", meta.version());
		assertEquals(RiskLevel.LOW, meta.riskLevel());
		assertEquals(ToolSource.LOCAL, meta.source());
		assertEquals("https://example.com/doc", meta.docUrl());
		assertNull(meta.server());
		assertTrue(meta.tags().isEmpty());
	}

	@Test
	public void testTagNormalizationDedupAndLowercase() {
		ToolMetadata meta = ToolMetadata.builder("t")
			.addTag(" Weather ")
			.addTag("weather")
			.addTag("")
			.addTag(null)
			.addTags(Set.of("API", "  search "))
			.build();
		assertEquals(Set.of("weather", "api", "search"), meta.tags());
	}

	@Test
	public void testNullRiskAndSourceDefault() {
		ToolMetadata meta = ToolMetadata.builder("t")
			.riskLevel(null)
			.source(null)
			.build();
		assertEquals(RiskLevel.LOW, meta.riskLevel());
		assertEquals(ToolSource.LOCAL, meta.source());
	}

	@Test
	public void testBlankNameRejected() {
		assertThrows(RuntimeException.class, () -> ToolMetadata.builder("   ").build());
		assertThrows(RuntimeException.class, () -> ToolMetadata.builder(null).build());
	}

	@Test
	public void testDescriptionNullBecomesEmpty() {
		ToolMetadata meta = ToolMetadata.builder("t").description(null).build();
		assertEquals("", meta.description());
	}

	@Test
	public void testCopyBuilderAndToBuilder() {
		ToolMetadata src = ToolMetadata.builder("a")
			.addTag("x")
			.riskLevel(RiskLevel.HIGH)
			.source(ToolSource.MCP)
			.server("srv")
			.build();
		ToolMetadata copy = ToolMetadata.from(src).riskLevel(RiskLevel.CRITICAL).build();
		assertEquals(RiskLevel.CRITICAL, copy.riskLevel());
		assertEquals("srv", copy.server());
		ToolMetadata rebuilt = copy.toBuilder().name("b").version("2").build();
		assertEquals("b", rebuilt.name());
		assertEquals("2", rebuilt.version());
		assertEquals(RiskLevel.CRITICAL, rebuilt.riskLevel());
	}

	@Test
	public void testTagsImmutable() {
		ToolMetadata meta = ToolMetadata.builder("t").addTag("a").build();
		assertThrows(UnsupportedOperationException.class, () -> meta.tags().add("x"));
	}

	@Test
	public void testNameSetterBlankRejected() {
		ToolMetadata meta = ToolMetadata.builder("t").build();
		assertThrows(RuntimeException.class, () -> meta.toBuilder().name("  ").build());
	}

	@Test
	public void testBuilderCopyPreservesFields() {
		ToolMetadata src = ToolMetadata.builder("keep")
			.description("d")
			.version("v")
			.docUrl("u")
			.server("sv")
			.addTag("z")
			.build();
		ToolMetadata copy = ToolMetadata.from(src).build();
		assertEquals(src.description(), copy.description());
		assertEquals(src.version(), copy.version());
		assertEquals(src.docUrl(), copy.docUrl());
		assertEquals(src.server(), copy.server());
		assertEquals(src.tags(), copy.tags());
	}
}
