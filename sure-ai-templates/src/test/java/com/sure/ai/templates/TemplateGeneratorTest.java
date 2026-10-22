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

package com.sure.ai.templates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 模板生成器单元测试：占位符解析/替换、目录生成、校验失败路径、jar 分支。
 */
public class TemplateGeneratorTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void listsAllThreeTemplates() {
		List<String> ids = Templates.list();
		assertTrue(ids.contains("rag-kb"));
		assertTrue(ids.contains("customer-service"));
		assertTrue(ids.contains("code-assistant"));
	}

	@Test
	public void resolveAppliesDefaults() {
		Map<String, String> resolved = TemplateGenerator.resolve(Map.of("projectName", "My KB"));
		assertEquals("com.example", resolved.get("groupId"));
		assertEquals("my-kb", resolved.get("artifactId"));
		assertEquals("com.example.mykb", resolved.get("mainPackage"));
		assertEquals("com/example/mykb", resolved.get("mainPackagePath"));
		assertEquals(TemplateGenerator.DEFAULT_SUREAI_VERSION, resolved.get("sureaiVersion"));
	}

	@Test
	public void resolveRejectsMissingProjectName() {
		assertThrows(IllegalArgumentException.class,
			() -> TemplateGenerator.resolve(null));
		assertThrows(IllegalArgumentException.class,
			() -> TemplateGenerator.resolve(Map.of("groupId", "com.acme")));
	}

	@Test
	public void applyReplacesKnownKeysAndKeepsUnknown() {
		String out = TemplateGenerator.apply(
			"{{artifactId}}/{{missing}}/{{projectName}}", Map.of(
				"artifactId", "my-app", "projectName", "My App"));
		assertEquals("my-app/{{missing}}/My App", out);
	}

	@Test
	public void applyHandlesNoPlaceholderAndUnclosedBrace() {
		assertEquals("plain text", TemplateGenerator.apply("plain text", Map.of()));
		assertEquals("a {{b", TemplateGenerator.apply("a {{b", Map.of("b", "x")));
		assertEquals("", TemplateGenerator.apply("", Map.of()));
	}

	@Test
	public void generateCreatesProjectStructureWithDefaults() throws IOException {
		Path target = tmp.getRoot().toPath().resolve("out");
		List<Path> files = TemplateGenerator.generate("rag-kb", target, Map.of("projectName", "My KB"));
		assertFalse("至少生成 pom 与两个源文件", files.size() < 4);

		String pom = Files.readString(target.resolve("pom.xml"), StandardCharsets.UTF_8);
		assertTrue(pom.contains("<artifactId>my-kb</artifactId>"));
		assertTrue(pom.contains("<groupId>com.example</groupId>"));
		assertTrue(pom.contains("<version>2.10.0-SNAPSHOT</version>"));

		Path main = target.resolve("src/main/java/com/example/mykb/Main.java");
		assertTrue("Main 按 mainPackagePath 生成", Files.exists(main));
		assertTrue(Files.readString(main).contains("package com.example.mykb;"));

		Path test = target.resolve("src/test/java/com/example/mykb/MainTest.java");
		assertTrue("MainTest 生成", Files.exists(test));
		assertTrue(Files.exists(target.resolve(".gitignore")));
		assertTrue(Files.exists(target.resolve("README.md")));
	}

	@Test
	public void generateHonorsCustomPlaceholders() throws IOException {
		Path target = tmp.getRoot().toPath().resolve("custom");
		TemplateGenerator.generate("customer-service", target, Map.of(
			"projectName", "Support Bot",
			"groupId", "com.acme",
			"artifactId", "support-bot"));
		String pom = Files.readString(target.resolve("pom.xml"));
		assertTrue(pom.contains("<artifactId>support-bot</artifactId>"));
		assertTrue(pom.contains("<groupId>com.acme</groupId>"));
		assertTrue(Files.exists(
			target.resolve("src/main/java/com/acme/supportbot/Main.java")));
	}

	@Test
	public void generateRejectsUnknownTemplate() {
		assertThrows(IllegalArgumentException.class,
			() -> TemplateGenerator.generate("nope", tmp.newFolder("x").toPath(),
				Map.of("projectName", "x")));
	}

	@Test
	public void generateRejectsNonEmptyTarget() throws IOException {
		Path target = tmp.newFolder("busy").toPath();
		Files.writeString(target.resolve("keep.txt"), "x");
		assertThrows(IllegalArgumentException.class,
			() -> TemplateGenerator.generate("rag-kb", target, Map.of("projectName", "x")));
	}

	@Test
	public void generateAllowsExistingEmptyTarget() throws IOException {
		Path target = tmp.newFolder("empty-dir").toPath();
		TemplateGenerator.generate("code-assistant", target, Map.of("projectName", "CA"));
		assertTrue(Files.exists(target.resolve("pom.xml")));
	}
	@Test
	public void generateFromJarClasspath() throws IOException {
		// 构造临时 jar：显式写目录条目 + 文件，null parent 隔离 classpath 资源，
		// 确保 getResource 命中 jar: URL 并走 jar 遍历分支
		Path jarPath = tmp.newFile("tpl.jar").toPath();
		String entryPrefix = "templates/rag-kb/";
		try (OutputStream fos = Files.newOutputStream(jarPath);
			JarOutputStream jar = new JarOutputStream(fos)) {
			jar.putNextEntry(new JarEntry(entryPrefix));
			jar.closeEntry();
			byte[] pom = "pom {{groupId}} {{mainPackagePath}}".getBytes(StandardCharsets.UTF_8);
			jar.putNextEntry(new JarEntry(entryPrefix + "pom.xml"));
			jar.write(pom);
			jar.closeEntry();
			byte[] main = "package {{mainPackage}};".getBytes(StandardCharsets.UTF_8);
			jar.putNextEntry(new JarEntry(entryPrefix + "src/main/java/{{mainPackagePath}}/Main.java"));
			jar.write(main);
			jar.closeEntry();
		}
		try (URLClassLoader loader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, null)) {
			Path target = tmp.newFolder("jar-out").toPath();
			TemplateGenerator.generate("rag-kb", target, Map.of("projectName", "From Jar"), loader);
			assertTrue(Files.exists(target.resolve("pom.xml")));
			assertTrue(Files.exists(
				target.resolve("src/main/java/com/example/fromjar/Main.java")));
			String pom = Files.readString(target.resolve("pom.xml"));
			assertTrue(pom.contains("com.example"));
			assertTrue(Files.readString(
				target.resolve("src/main/java/com/example/fromjar/Main.java"))
				.contains("package com.example.fromjar;"));
		}
	}
}
