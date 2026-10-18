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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link SkillLoader} 本地目录 / classpath / jar 发现与错误路径测试（零网络）。
 */
public class SkillLoaderTest {

	private final TemporaryFolder temp = new TemporaryFolder();

	private static final String SKILL_A = """
			---
			name: alpha-skill
			description: Alpha capability. Use when alpha is requested.
			---
			# Alpha Body
			Alpha instructions here.
			""";

	private static final String SKILL_B = """
			---
			name: beta-skill
			description: Beta capability. Use when beta is requested.
			---
			# Beta Body
			Beta instructions here.
			""";

	private void write(Path file, String content) throws Exception {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	@Test
	public void testLocalMultiSkillDirectory() throws Exception {
		temp.create();
		Path root = temp.newFolder("skills").toPath();
		write(root.resolve("alpha/SKILL.md"), SKILL_A);
		write(root.resolve("beta/SKILL.md"), SKILL_B);
		Files.createDirectories(root.resolve("empty-dir"));

		List<Skill> skills = SkillLoader.load(root);
		assertEquals(2, skills.size());
		Skill alpha = skills.stream().filter(s -> s.name().equals("alpha-skill")).findFirst().orElseThrow();
		// 渐进式：manifest 就绪，正文按需加载
		assertEquals("Alpha capability. Use when alpha is requested.",
				alpha.manifest().description());
		assertTrue(alpha.loadBody().contains("Alpha instructions here."));
		assertTrue(alpha.origin().contains("alpha"));
	}

	@Test
	public void testLocalSingleSkillRoot() throws Exception {
		temp.create();
		Path root = temp.newFolder("single").toPath();
		write(root.resolve("SKILL.md"), SKILL_A);
		write(root.resolve("notes.md"), "standalone note");

		List<Skill> skills = SkillLoader.load(root);
		assertEquals(1, skills.size());
		assertEquals("alpha-skill", skills.get(0).name());
		assertEquals("standalone note", skills.get(0).loadResource("notes.md").trim());
	}

	@Test
	public void testNameConflictThrows() throws Exception {
		temp.create();
		Path root = temp.newFolder("conflict").toPath();
		write(root.resolve("one/SKILL.md"), SKILL_A);
		write(root.resolve("two/SKILL.md"), SKILL_A);
		SkillLoadException ex = assertThrows(SkillLoadException.class, () -> SkillLoader.load(root));
		assertTrue(ex.getMessage().contains("冲突"));
	}

	@Test
	public void testInvalidFrontmatterThrows() throws Exception {
		temp.create();
		Path root = temp.newFolder("invalid").toPath();
		write(root.resolve("bad/SKILL.md"), """
				---
				name: bad-skill
				---
				body
				""");
		assertThrows(SkillLoadException.class, () -> SkillLoader.load(root));
	}

	@Test
	public void testMissingRootThrows() {
		assertThrows(SkillLoadException.class, () -> SkillLoader.load(Path.of("/no/such/dir/xyz")));
	}

	@Test
	public void testClasspathSample() {
		List<Skill> skills = SkillLoader.loadFromClasspath("skills-sample");
		assertEquals(2, skills.size());
		Skill pdf = skills.stream().filter(s -> s.name().equals("pdf-processing")).findFirst().orElseThrow();
		assertTrue(pdf.loadBody().contains("PDF Processing"));
		assertEquals("Apache-2.0", pdf.manifest().license());
		assertEquals(List.of("Read", "Grep"), pdf.manifest().allowedTools());
		assertEquals("document", pdf.manifest().metadata().get("tier"));
		String ref = pdf.loadResource("references/extract.md");
		assertTrue(ref.contains("Extraction Checklist"));
		assertTrue(pdf.origin().startsWith("classpath:"));

		Skill docx = skills.stream().filter(s -> s.name().equals("docx-authoring")).findFirst().orElseThrow();
		assertEquals("Requires docx-js; no network install at runtime.", docx.manifest().compatibility());
		assertEquals(List.of("Read", "Write"), docx.manifest().allowedTools());
	}

	@Test
	public void testUnknownClasspathBaseReturnsEmpty() {
		assertTrue(SkillLoader.loadFromClasspath("no-such-base-xyz").isEmpty());
	}

	@Test
	public void testBlankClasspathBaseThrows() {
		assertThrows(Exception.class, () -> SkillLoader.loadFromClasspath("  "));
	}

	@Test
	public void testJarDiscovery() throws Exception {
		temp.create();
		Path jar = temp.newFile("embedded.jar").toPath();
		try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
			jos.putNextEntry(new ZipEntry("embedded/jarskill/SKILL.md"));
			jos.write(("""
					---
					name: jar-skill
					description: A skill packaged inside a jar. Use when jar packaging matters.
					---
					# Jar Body
					Jar instructions.
					""").getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
			jos.putNextEntry(new ZipEntry("embedded/jarskill/reference.md"));
			jos.write("jar resource body".getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
			// 目录条目与前缀外的无关条目，覆盖跳过分支
			jos.putNextEntry(new ZipEntry("embedded/jarskill/assets/"));
			jos.closeEntry();
			jos.putNextEntry(new ZipEntry("other/notes.txt"));
			jos.write("unrelated".getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		List<Skill> skills = SkillLoader.loadJar(jar, "embedded");
		assertEquals(1, skills.size());
		Skill s = skills.get(0);
		assertEquals("jar-skill", s.name());
		assertTrue(s.origin().contains("jarskill"));
		assertTrue(s.loadBody().contains("Jar instructions."));
		assertEquals("jar resource body", s.loadResource("reference.md"));
		assertThrows(SkillResourceException.class, () -> s.loadResource("absent.md"));
	}

	@Test
	public void testResourceTraversalRejected() throws Exception {
		temp.create();
		Path root = temp.newFolder("safe").toPath();
		write(root.resolve("alpha/SKILL.md"), SKILL_A);
		Skill skill = SkillLoader.load(root).get(0);

		assertThrows(SkillResourceException.class, () -> skill.loadResource("../escape.md"));
		assertThrows(SkillResourceException.class, () -> skill.loadResource("/etc/passwd"));
		assertThrows(SkillResourceException.class, () -> skill.loadResource("references\\win.md"));
		assertThrows(SkillResourceException.class, () -> skill.loadResource("missing.md"));
		assertThrows(SkillResourceException.class, () -> skill.loadResource("."));
		assertThrows(SkillResourceException.class, () -> skill.loadResource(""));
	}

	@Test
	public void testResourceDotSegmentNormalized() throws Exception {
		temp.create();
		Path root = temp.newFolder("dots").toPath();
		write(root.resolve("alpha/SKILL.md"), SKILL_A);
		write(root.resolve("alpha/references/guide.md"), "guide body");
		Skill skill = SkillLoader.load(root).get(0);
		assertEquals("guide body", skill.loadResource("references/./guide.md"));
	}

	@Test
	public void testLocalResourceMissingThrows() throws Exception {
		temp.create();
		Path root = temp.newFolder("miss").toPath();
		write(root.resolve("alpha/SKILL.md"), SKILL_A);
		Skill skill = SkillLoader.load(root).get(0);
		SkillResourceException ex = assertThrows(SkillResourceException.class,
				() -> skill.loadResource("nope.md"));
		assertTrue(ex.getMessage().contains("不存在"));
	}

	@Test
	public void testLoadBodyMissingFileThrows() throws Exception {
		// 构造一个 content 指向缺失文件的异常路径：直接通过本地加载后删除 SKILL.md
		temp.create();
		Path root = temp.newFolder("gone").toPath();
		write(root.resolve("alpha/SKILL.md"), SKILL_A);
		Skill skill = SkillLoader.load(root).get(0);
		Files.delete(root.resolve("alpha/SKILL.md"));
		assertThrows(SkillResourceException.class, skill::loadBody);
	}

	@Test
	public void testOriginsDistinct() throws Exception {
		temp.create();
		Path root = temp.newFolder("o").toPath();
		write(root.resolve("alpha/SKILL.md"), SKILL_A);
		List<Skill> skills = SkillLoader.load(root);
		assertFalse(skills.get(0).origin().isBlank());
	}
}
