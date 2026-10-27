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

package com.sure.ai.agent.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * {@link SkillManifest} 规范化与规范校验测试。
 */
public class SkillManifestTest {

	private static SkillManifest parse(String md) {
		return SkillManifest.from(SkillFrontmatterParser.parse(md));
	}

	@Test
	public void testFullManifest() {
		String md = """
				---
				name: pdf-processing
				description: Extract text from PDFs. Use on PDF tasks.
				license: Apache-2.0
				compatibility: Requires pdfplumber.
				allowed-tools: Read, Grep
				metadata:
				  tier: document
				unknown-field: keep-me
				---
				body
				""";
		SkillManifest m = parse(md);
		assertEquals("pdf-processing", m.name());
		assertEquals("Extract text from PDFs. Use on PDF tasks.", m.description());
		assertEquals("Apache-2.0", m.license());
		assertEquals("Requires pdfplumber.", m.compatibility());
		assertEquals(List.of("Read", "Grep"), m.allowedTools());
		assertEquals("document", m.metadata().get("tier"));
		assertEquals("keep-me", m.extraProperties().get("unknown-field"));
	}

	@Test
	public void testListAllowedTools() {
		SkillManifest m = parse("""
				---
				name: list-tools
				description: d
				allowed-tools:
				  - Read
				  - Write
				---
				body
				""");
		assertEquals(List.of("Read", "Write"), m.allowedTools());
	}

	@Test
	public void testMissingNameThrows() {
		SkillLoadException ex = assertThrows(SkillLoadException.class, () -> parse("""
				---
				description: only description
				---
				body
				"""));
		assertTrue(ex.getMessage().contains("name"));
	}

	@Test
	public void testMissingDescriptionThrows() {
		assertThrows(SkillLoadException.class, () -> parse("""
				---
				name: no-desc
				---
				body
				"""));
	}

	@Test
	public void testInvalidNameUppercaseThrows() {
		assertThrows(SkillLoadException.class, () -> parse("""
				---
				name: Bad-Name
				description: d
				---
				body
				"""));
	}

	@Test
	public void testNameTooLongThrows() {
		String longName = "a".repeat(SkillManifest.NAME_MAX_LENGTH + 1);
		assertThrows(SkillLoadException.class, () -> parse("""
				---
				name: %s
				description: d
				---
				body
				""".formatted(longName)));
	}

	@Test
	public void testReservedNameThrows() {
		assertThrows(SkillLoadException.class, () -> parse("""
				---
				name: claude
				description: d
				---
				body
				"""));
		assertThrows(SkillLoadException.class, () -> parse("""
				---
				name: anthropic
				description: d
				---
				body
				"""));
	}

	@Test
	public void testAngleBracketInNameThrows() {
		assertThrows(SkillLoadException.class, () -> parse("""
				---
				name: a<b
				description: d
				---
				body
				"""));
	}

	@Test
	public void testDescriptionTooLongThrows() {
		String longDesc = "x".repeat(SkillManifest.DESCRIPTION_MAX_LENGTH + 1);
		assertThrows(SkillLoadException.class, () -> new SkillManifest(
				"ok-name", longDesc, null, null, List.of(), Map.of(), Map.of()));
	}

	@Test
	public void testAngleBracketInDescriptionThrows() {
		assertThrows(SkillLoadException.class, () -> new SkillManifest(
				"ok-name", "contains <tag>", null, null, List.of(), Map.of(), Map.of()));
	}

	@Test
	public void testCompatibilityTooLongThrows() {
		String longC = "c".repeat(SkillManifest.COMPATIBILITY_MAX_LENGTH + 1);
		assertThrows(SkillLoadException.class, () -> new SkillManifest(
				"ok-name", "desc", null, longC, List.of(), Map.of(), Map.of()));
	}

	@Test
	public void testDefaultsWhenOptionalAbsent() {
		SkillManifest m = parse("""
				---
				name: bare-skill
				description: A bare skill.
				---
				body
				""");
		assertEquals(null, m.license());
		assertEquals(null, m.compatibility());
		assertTrue(m.allowedTools().isEmpty());
		assertTrue(m.metadata().isEmpty());
		assertTrue(m.extraProperties().isEmpty());
	}

	@Test
	public void testBlankNameDirectConstructorThrows() {
		assertThrows(SkillLoadException.class,
				() -> new SkillManifest("   ", "desc", null, null, List.of(), Map.of(), Map.of()));
	}

	@Test
	public void testBlankDescriptionDirectConstructorThrows() {
		assertThrows(SkillLoadException.class,
				() -> new SkillManifest("ok-name", "  ", null, null, List.of(), Map.of(), Map.of()));
	}
}
