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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * {@link SkillFrontmatterParser} 单元测试：标量/引号/列表/嵌套表/非法形态。
 */
public class SkillFrontmatterParserTest {

	@Test
	public void testScalarFieldsAndBody() {
		String md = """
				---
				name: demo-skill
				description: Does demos when the user asks.
				license: MIT
				---
				# Demo Body
				Step one.
				""";
		SkillFrontmatter fm = SkillFrontmatterParser.parse(md);
		assertEquals("demo-skill", fm.fields().get("name"));
		assertEquals("Does demos when the user asks.", fm.fields().get("description"));
		assertEquals("MIT", fm.fields().get("license"));
		assertTrue(fm.body().contains("# Demo Body"));
		assertTrue(fm.body().contains("Step one."));
	}

	@Test
	public void testQuotedValues() {
		String md = """
				---
				name: quoted
				description: "Say \\"hi\\" and \\\\ backslash"
				single: 'it''s literal'
				---
				body
				""";
		SkillFrontmatter fm = SkillFrontmatterParser.parse(md);
		assertEquals("Say \"hi\" and \\ backslash", fm.fields().get("description"));
		assertEquals("it''s literal", fm.fields().get("single"));
	}

	@Test
	public void testBlockList() {
		String md = """
				---
				name: list-skill
				description: Has a tool list.
				allowed-tools:
				  - Read
				  - Grep
				  - Write
				---
				body
				""";
		SkillFrontmatter fm = SkillFrontmatterParser.parse(md);
		Object tools = fm.fields().get("allowed-tools");
		assertTrue(tools instanceof List);
		assertEquals(List.of("Read", "Grep", "Write"), tools);
	}

	@Test
	public void testNestedMetadataMap() {
		String md = """
				---
				name: meta-skill
				description: Has metadata.
				metadata:
				  tier: document
				  owner: sureai
				---
				body
				""";
		SkillFrontmatter fm = SkillFrontmatterParser.parse(md);
		@SuppressWarnings("unchecked")
		Map<String, String> meta = (Map<String, String>) fm.fields().get("metadata");
		assertEquals("document", meta.get("tier"));
		assertEquals("sureai", meta.get("owner"));
	}

	@Test
	public void testCommentLinesIgnored() {
		String md = """
				---
				# a comment
				name: comment-skill
				description: Skips comments.
				---
				body
				""";
		SkillFrontmatter fm = SkillFrontmatterParser.parse(md);
		assertEquals("comment-skill", fm.fields().get("name"));
	}

	@Test
	public void testMissingFrontmatterThrows() {
		SkillLoadException ex = assertThrows(SkillLoadException.class,
				() -> SkillFrontmatterParser.parse("# No frontmatter\nbody"));
		assertTrue(ex.getMessage().contains("frontmatter"));
	}

	@Test
	public void testMissingClosingDelimiterThrows() {
		String md = "---\nname: x\ndescription: y\nbody never closes";
		assertThrows(SkillLoadException.class, () -> SkillFrontmatterParser.parse(md));
	}

	@Test
	public void testDuplicateKeyThrows() {
		String md = """
				---
				name: a
				name: b
				description: d
				---
				body
				""";
		SkillLoadException ex = assertThrows(SkillLoadException.class,
				() -> SkillFrontmatterParser.parse(md));
		assertTrue(ex.getMessage().contains("重复键"));
	}

	@Test
	public void testIllegalTopLineThrows() {
		String md = "---\nno colon here\ndescription: d\n---\nbody\n";
		assertThrows(SkillLoadException.class, () -> SkillFrontmatterParser.parse(md));
	}

	@Test
	public void testIndentedWithoutTopKeyThrows() {
		String md = "---\n  - orphan\n---\nbody\n";
		assertThrows(SkillLoadException.class, () -> SkillFrontmatterParser.parse(md));
	}

	@Test
	public void testIllegalKeyNameThrows() {
		String md = "---\nbad key!: v\ndescription: d\n---\nbody\n";
		assertThrows(SkillLoadException.class, () -> SkillFrontmatterParser.parse(md));
	}

	@Test
	public void testEmptyInputThrows() {
		assertThrows(SkillLoadException.class, () -> SkillFrontmatterParser.parse(""));
		assertThrows(SkillLoadException.class, () -> SkillFrontmatterParser.parse(null));
	}

	@Test
	public void testUnquotePlainPassthrough() {
		assertEquals("plain", SkillFrontmatterParser.unquote("plain"));
		assertEquals("", SkillFrontmatterParser.unquote(""));
	}

	@Test
	public void testDoubleQuoteEscapes() {
		SkillFrontmatter fm = SkillFrontmatterParser.parse("""
				---
				name: esc
				description: "line one\\nline two \\\\ end"
				---
				body
				""");
		assertEquals("line one\nline two \\ end", fm.fields().get("description"));
	}

	@Test
	public void testIllegalIndentedLineThrows() {
		assertThrows(SkillLoadException.class, () -> SkillFrontmatterParser.parse("""
				---
				name: bad-indent
				description: d
				  orphan line
				---
				body
				"""));
	}
}
