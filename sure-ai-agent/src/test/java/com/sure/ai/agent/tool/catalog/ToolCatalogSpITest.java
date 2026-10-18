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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.Test;

/**
 * {@link ToolProvider} ServiceLoader 加载与内置示例提供者测试。
 */
public class ToolCatalogSpITest {

	@Test
	public void testServiceLoaderFindsBuiltinSampleProvider() {
		List<ToolProvider> providers = ToolProviders.load();
		assertTrue(providers.stream().anyMatch(p -> p instanceof BuiltinSampleToolProvider));
	}

	@Test
	public void testLoadIntoRegistersBuiltinContributions() {
		ToolCatalog catalog = new ToolCatalog();
		int n = ToolProviders.loadInto(catalog);
		assertEquals(2, n);
		assertEquals(2, catalog.findByTag("sample").size());
		assertTrue(catalog.get("sample.echo").isPresent());
		assertTrue(catalog.get("sample.ping").isPresent());
	}

	@Test
	public void testBuiltinHandlersExecute() throws Exception {
		ToolCatalog catalog = new ToolCatalog();
		ToolProviders.loadInto(catalog);
		com.sure.ai.internal.json.JsonObject args = com.sure.ai.internal.json.Json.object();
		args.set("text", "hi");
		assertEquals("echo:hi", catalog.get("sample.echo").orElseThrow().handler().execute(args));
		assertEquals("pong", catalog.get("sample.ping").orElseThrow().handler().execute(null));
	}

	@Test
	public void testNullContributionListSkipped() {
		ToolCatalog catalog = new ToolCatalog();
		ToolProvider nullListProvider = () -> null;
		int n = ToolProviders.loadInto(catalog, List.of(nullListProvider));
		assertEquals(0, n);
		assertTrue(catalog.isEmpty());
	}

	@Test
	public void testCustomProviderContributionRegistered() throws Exception {
		ToolCatalog catalog = new ToolCatalog();
		ToolProvider custom = () -> List.of(new ToolContribution(
			com.sure.ai.model.ToolFunction.of("custom.tool", "自定义", "{}"),
			args -> "custom-ok",
			ToolMetadata.builder("custom.tool").addTag("custom").build()));
		int n = ToolProviders.loadInto(catalog, List.of(custom));
		assertEquals(1, n);
		assertEquals("custom-ok", catalog.get("custom.tool").orElseThrow().handler().execute(null));
	}

	@Test
	public void testLoadIntoNullProvidersSafe() {
		assertEquals(0, ToolProviders.loadInto(new ToolCatalog(), (Iterable<ToolProvider>) null));
	}

	@Test
	public void testNullCatalogRejected() {
		assertThrows(RuntimeException.class, () -> ToolProviders.loadInto(null));
		assertThrows(RuntimeException.class,
			() -> ToolProviders.loadInto(null, List.of()));
	}

	@Test
	public void testPrivateConstructorRejectsInstantiation() throws Exception {
		Constructor<ToolProviders> ctor = ToolProviders.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("应抛出 AssertionError");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof AssertionError);
		}
	}
}
