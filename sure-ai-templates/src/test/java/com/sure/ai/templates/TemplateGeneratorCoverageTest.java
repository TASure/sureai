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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
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
}
