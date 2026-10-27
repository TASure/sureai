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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Test;

/**
 * 边界分支覆盖：私有构造 / 未知模板 / slug 归一 / enumerate 异常协议。
 *
 * @author sureai
 * @since 3.0.0
 */
public class TemplateGeneratorCoverageTest {

	@Test
	public void privateConstructorIsGuarded() throws Exception {
		Constructor<TemplateGenerator> ctor = TemplateGenerator.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			throw new AssertionError("私有构造应抛 AssertionError");
		} catch (java.lang.reflect.InvocationTargetException e) {
			assertTrue("私有构造守卫为 AssertionError", e.getCause() instanceof AssertionError);
		}
	}

	@Test
	public void ofRejectsUnknownAndBlankId() {
		assertThrows(IllegalArgumentException.class, () -> Templates.of("nope"));
		assertThrows(IllegalArgumentException.class, () -> Templates.of(""));
		assertThrows(IllegalArgumentException.class, () -> Templates.of(null));
	}

	@Test
	public void displayNameIsHumanReadable() {
		assertTrue(!Templates.of("rag-kb").displayName().isBlank());
		assertTrue(!Templates.of("customer-service").displayName().isBlank());
		assertTrue(!Templates.of("code-assistant").displayName().isBlank());
	}

	@Test
	public void slugNormalizesWeirdNames() {
		assertEquals("", Templates.slug(null));
		assertEquals("demo-kb", Templates.slug("Demo KB"));
		assertEquals("demo-kb", Templates.slug("  Demo  KB!!  "));
		assertEquals("a-b-c", Templates.slug("A--B__C"));
	}

	@Test
	public void enumerateRejectsUnsupportedProtocol() {
		assertThrows(IOException.class, () -> TemplateGenerator.enumerate(
			TemplateGeneratorCoverageTest.class.getClassLoader(),
			new URL("http://example.com/templates/x"), "/templates/x"));
	}

	@Test
	public void generateFailsWhenResourceMissing() throws Exception {
		// 自定义 loader 的 getResource 返回 null（不委托父）→ 资源缺失 IOException
		ClassLoader missing = new ClassLoader(null) {
			@Override
			public URL getResource(String name) {
				return null;
			}
		};
		assertThrows(IOException.class, () -> TemplateGenerator.generate(
			"rag-kb", Files.createTempDirectory("sureai-missing"),
			Map.of("projectName", "X"), missing));
	}

	@Test
	public void enumerateJarUrlWithIllegalEncodingFails() throws Exception {
		// jar URL 的 jar 部分含非法编码：URI.create 失败 → catch → IOException
		assertThrows(IOException.class, () -> TemplateGenerator.enumerate(
			TemplateGeneratorCoverageTest.class.getClassLoader(),
			new URL("jar:file:/tmp/x%zz.jar!/"), "/templates/x"));
	}

	@Test
	public void enumerateFileUrlWithIllegalEncodingFails() throws Exception {
		// file 协议但路径含非法转义：Path.of(root.toURI()) 抛异常 → 转 IOException
		assertThrows(IOException.class, () -> TemplateGenerator.enumerate(
			TemplateGeneratorCoverageTest.class.getClassLoader(),
			new URL("file:/tmp/x%zz.yaml"), "/templates/x"));
	}

	@Test
	public void resolveDefaultVersionFallsBackOnNullBlankAndMissingKey() throws Exception {
		assertEquals("3.0.0", TemplateGenerator.resolveDefaultVersion(null));
		assertEquals("3.0.0", TemplateGenerator.resolveDefaultVersion(
			new ByteArrayInputStream("sureai.version=  \n".getBytes(StandardCharsets.UTF_8))));
		assertEquals("3.0.0", TemplateGenerator.resolveDefaultVersion(
			new ByteArrayInputStream("other.key=1\n".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	public void resolveDefaultVersionParsesAndTrimsValue() throws Exception {
		assertEquals("4.0.0-RC1", TemplateGenerator.resolveDefaultVersion(
			new ByteArrayInputStream("sureai.version= 4.0.0-RC1 \n".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	public void resolveDefaultVersionFallsBackOnIoError() throws Exception {
		InputStream broken = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("boom");
			}
		};
		assertEquals("3.0.0", TemplateGenerator.resolveDefaultVersion(broken));
	}

	@Test
	public void defaultVersionIsResolvedFromClasspath() {
		assertTrue("构建期注入版本不应为空白", !TemplateGenerator.DEFAULT_SUREAI_VERSION.isBlank());
	}
}
