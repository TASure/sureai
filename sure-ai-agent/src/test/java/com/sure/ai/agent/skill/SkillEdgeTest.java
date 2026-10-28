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
import static org.junit.Assume.assumeTrue;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import com.sure.ai.agent.tool.ToolRegistry;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 技能模块补充边界测试：单技能 classpath 直挂、jar 直挂、路径守卫、非标量字段丢弃。
 */
public class SkillEdgeTest {

	private final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void testClasspathSingleSkillDirectBase() {
		List<Skill> skills = SkillLoader.loadFromClasspath("skills-single");
		assertEquals(1, skills.size());
		Skill s = skills.get(0);
		assertEquals("standalone-skill", s.name());
		assertTrue(s.loadBody().contains("Direct base instructions."));
	}

	@Test
	public void testJarDirectAndNested() throws Exception {
		this.temp.create();
		Path jar = this.temp.newFile("both.jar").toPath();
		try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
			jos.putNextEntry(new ZipEntry("embedded/SKILL.md"));
			jos.write(("""
					---
					name: direct-jar-skill
					description: Direct jar skill at the base root.
					---
					# Direct
					direct body
					""").getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
			jos.putNextEntry(new ZipEntry("embedded/pkg/SKILL.md"));
			jos.write(("""
					---
					name: nested-jar-skill
					description: Nested jar skill under a subdirectory.
					---
					# Nested
					nested body
					""").getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		List<Skill> skills = SkillLoader.loadJar(jar, "embedded");
		assertEquals(2, skills.size());
		assertTrue(skills.stream().anyMatch(s -> s.name().equals("direct-jar-skill")));
		assertTrue(skills.stream().anyMatch(s -> s.name().equals("nested-jar-skill")));
	}

	@Test
	public void testPathGuards() {
		assertThrows(SkillResourceException.class, () -> SkillPaths.safeRelative("a\\b.md"));
		assertThrows(SkillResourceException.class, () -> SkillPaths.safeRelative("\\win.md"));
		assertThrows(SkillResourceException.class, () -> SkillPaths.safeRelative("a/../escape.md"));
		assertThrows(SkillResourceException.class, () -> SkillPaths.safeRelative("a\0b.md"));
		assertThrows(SkillResourceException.class, () -> SkillPaths.safeRelative(null));
		assertEquals("a/b.md", SkillPaths.safeRelative("a//b.md"));
	}

	@Test
	public void testClasspathMissingResourceThrows() {
		Skill s = SkillLoader.loadFromClasspath("skills-single").get(0);
		SkillResourceException ex = assertThrows(SkillResourceException.class,
				() -> s.loadResource("missing.md"));
		assertTrue(ex.getMessage().contains("missing.md"));
	}

	@Test
	public void testNonScalarExtraKeyDroppedAndNonMapMetadataIgnored() {
		SkillManifest m = SkillManifest.from(SkillFrontmatterParser.parse("""
				---
				name: edge-skill
				description: Edge coverage skill.
				allowed-tools:
				  - Read
				metadata: [a, b]
				---
				body
				"""));
		assertTrue(m.extraProperties().isEmpty());
		assertTrue(m.metadata().isEmpty());
		assertEquals(List.of("Read"), m.allowedTools());
	}

	@Test
	public void testLocalOriginString() throws Exception {
		this.temp.create();
		Path root = this.temp.newFolder("o2").toPath();
		java.nio.file.Files.createDirectories(root.resolve("x"));
		java.nio.file.Files.writeString(root.resolve("x/SKILL.md"), """
				---
				name: origin-skill
				description: Origin check.
				---
				body
				""", StandardCharsets.UTF_8);
		Skill s = SkillLoader.load(root).get(0);
		assertTrue(s.origin().contains("x"));
	}

	@Test
	public void testLoadJarMissingFileThrows() {
		assertThrows(SkillLoadException.class,
				() -> SkillLoader.loadJar(Path.of("/no/such/file.jar"), "x"));
	}

	@Test
	public void testClasspathBaseSlashNormalization() {
		List<Skill> skills = SkillLoader.loadFromClasspath(" /skills-sample/ ");
		assertEquals(2, skills.size());
	}

	@Test
	public void testExceptionCauseConstructors() {
		RuntimeException cause = new RuntimeException("root");
		SkillLoadException loadEx = new SkillLoadException("load", cause);
		assertEquals(cause, loadEx.getCause());
		SkillResourceException resEx = new SkillResourceException("res", cause);
		assertEquals(cause, resEx.getCause());
	}

	@Test
	public void testRegisterAllEmptyNoOp() {
		ToolRegistry registry = new ToolRegistry();
		SkillTool.registerAll(List.of(), registry);
		assertEquals(0, registry.size());
	}

	@Test
	public void testNormalizeBaseEmptyThrows() {
		assertThrows(SkillLoadException.class, () -> SkillLoader.loadFromClasspath("/"));
		assertThrows(SkillLoadException.class,
				() -> SkillLoader.loadJar(Path.of("/x.jar"), "///"));
	}

	@Test
	public void testClasspathEnumerationIOExceptionWrapped() {
		ClassLoader failing = new ClassLoader() {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				throw new IOException("boom");
			}
		};
		assertThrows(SkillLoadException.class,
				() -> SkillLoader.loadFromClasspath("x", failing));
	}

	@Test
	public void testMalformedFileRootUrlWrapped() throws Exception {
		URL bad = new URL("file:///tmp/a b");
		ClassLoader loader = new ClassLoader() {
			@Override
			public Enumeration<URL> getResources(String name) {
				return java.util.Collections.enumeration(java.util.List.of(bad));
			}
		};
		assertThrows(SkillLoadException.class,
				() -> SkillLoader.loadFromClasspath("x", loader));
	}

	@Test
	public void testClasspathStreamIOExceptionWrapped() {
		ClassLoader broken = new ClassLoader() {
			@Override
			public InputStream getResourceAsStream(String name) {
				return new InputStream() {
					@Override
					public int read() throws IOException {
						throw new IOException("read boom");
					}
				};
			}
		};
		ClasspathSkillContent content = new ClasspathSkillContent("p", broken);
		assertThrows(SkillResourceException.class, content::readBody);
	}

	@Test
	public void testLocalUnreadableSkillMdThrows() throws Exception {
		assumeTrue("POSIX 权限视图仅在 Unix 系可用，Windows 跳过",
				FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		this.temp.create();
		Path dir = this.temp.newFolder("deny").toPath();
		Path md = dir.resolve("SKILL.md");
		Files.writeString(md, """
				---
				name: deny-skill
				description: Unreadable file.
				---
				body
				""", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(md, PosixFilePermissions.fromString("---------"));
		try {
			if (!Files.isReadable(md)) {
				assertThrows(SkillLoadException.class, () -> SkillLoader.load(dir));
			}
		} finally {
			Files.setPosixFilePermissions(md, PosixFilePermissions.fromString("rw-r--r--"));
		}
	}

	@Test
	public void testUnreadableRootDirScanThrows() throws Exception {
		assumeTrue("POSIX 权限视图仅在 Unix 系可用，Windows 跳过",
				FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		this.temp.create();
		Path root = this.temp.newFolder("locked").toPath();
		Files.createDirectories(root.resolve("x"));
		Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("---------"));
		try {
			if (!Files.isReadable(root)) {
				assertThrows(SkillLoadException.class, () -> SkillLoader.load(root));
			}
		} finally {
			Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}
}
