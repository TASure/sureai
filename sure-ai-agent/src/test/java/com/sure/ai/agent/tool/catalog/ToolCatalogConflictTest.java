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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ToolFunction;

/**
 * 同名冲突三策略、委托镜像与导出测试。
 */
public class ToolCatalogConflictTest {

	private static ToolFunction fn(String name) {
		return ToolFunction.of(name, "d-" + name, "{}");
	}

	@Test
	public void testFailStrategyRejectsDuplicate() throws Exception {
		ToolCatalog catalog = new ToolCatalog().conflictStrategy(ConflictStrategy.FAIL);
		catalog.register(fn("dup"), args -> "first");
		assertThrows(IllegalStateException.class,
			() -> catalog.register(fn("dup"), args -> "second"));
		assertEquals(1, catalog.size());
		assertEquals("first", catalog.get("dup").orElseThrow().handler().execute(null));
	}

	@Test
	public void testOverwriteReplaces() throws Exception {
		ToolCatalog catalog = new ToolCatalog().conflictStrategy(ConflictStrategy.OVERWRITE);
		catalog.register(fn("dup"), args -> "first");
		ToolEntry replaced = catalog.register(fn("dup"), args -> "second");
		assertEquals(1, catalog.size());
		assertEquals("second", replaced.handler().execute(null));
	}

	@Test
	public void testRenameKeepsBoth() throws Exception {
		ToolCatalog catalog = new ToolCatalog().conflictStrategy(ConflictStrategy.RENAME);
		catalog.register(fn("dup"), args -> "first");
		ToolEntry second = catalog.register(fn("dup"), args -> "second");
		assertEquals(2, catalog.size());
		assertTrue(catalog.get("dup").isPresent());
		assertTrue(catalog.get("dup__1").isPresent());
		assertEquals("second", second.handler().execute(null));
		// 连续第三次冲突继续递增后缀
		ToolEntry third = catalog.register(fn("dup"), args -> "third");
		assertEquals(3, catalog.size());
		assertTrue(catalog.get("dup__2").isPresent());
		assertEquals("third", third.handler().execute(null));
	}

	@Test
	public void testPerCallStrategyOverridesDefault() {
		ToolCatalog catalog = new ToolCatalog().conflictStrategy(ConflictStrategy.OVERWRITE);
		catalog.register(fn("x"), args -> "a");
		// 单次 FAIL 覆盖默认 OVERWRITE
		assertThrows(IllegalStateException.class, () -> catalog.register(
			new ToolEntry(fn("x"), args -> "b", ToolMetadata.builder("x").build()),
			ConflictStrategy.FAIL));
		assertEquals(1, catalog.size());
	}

	@Test
	public void testDelegateMirrorOnRegisterAndUnregister() {
		ToolRegistry registry = new ToolRegistry();
		ToolCatalog catalog = new ToolCatalog(registry);
		catalog.register(fn("a"), args -> "A");
		assertTrue(registry.getHandler("a").isPresent());
		catalog.unregister("a");
		assertFalse(registry.getHandler("a").isPresent());
		assertTrue(catalog.isEmpty());
	}

	@Test
	public void testClearDoesNotClearDelegate() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(fn("external"), args -> "ext");
		ToolCatalog catalog = new ToolCatalog(registry);
		catalog.register(fn("mine"), args -> "m");
		catalog.clear();
		assertTrue(catalog.isEmpty());
		assertTrue(registry.getHandler("external").isPresent());
	}

	@Test
	public void testExportAllAndSelectiveAndNames() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.register(fn("a"), args -> "A", ToolMetadata.builder("a").addTag("keep").build());
		catalog.register(fn("b"), args -> "B", ToolMetadata.builder("b").addTag("drop").build());
		ToolRegistry target = new ToolRegistry();
		int all = catalog.exportTo(target);
		assertEquals(2, all);
		assertEquals(2, target.size());

		ToolRegistry selective = new ToolRegistry();
		int n = catalog.exportTo(selective, e -> e.metadata().tags().contains("keep"));
		assertEquals(1, n);
		assertTrue(selective.getHandler("a").isPresent());
		assertFalse(selective.getHandler("b").isPresent());

		ToolRegistry named = new ToolRegistry();
		int k = catalog.exportNames(Set.of("b"), named);
		assertEquals(1, k);
		assertTrue(named.getHandler("b").isPresent());
	}

	@Test
	public void testExportNamesUnknownThrows() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.register(fn("a"), args -> "A");
		assertThrows(IllegalArgumentException.class,
			() -> catalog.exportNames(Set.of("a", "ghost"), new ToolRegistry()));
	}

	@Test
	public void testExportNullPredicateSelectsAll() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.register(fn("a"), args -> "A");
		ToolRegistry target = new ToolRegistry();
		assertEquals(1, catalog.exportTo(target, null));
	}

	@Test
	public void testNullArgumentsRejected() {
		ToolCatalog catalog = new ToolCatalog();
		assertThrows(RuntimeException.class, () -> catalog.register(null, args -> "x"));
		assertThrows(RuntimeException.class,
			() -> catalog.register(fn("a"), args -> "x", (ToolMetadata) null));
		assertThrows(RuntimeException.class, () -> catalog.exportTo(null));
		assertThrows(RuntimeException.class, () -> catalog.exportNames(null, new ToolRegistry()));
		assertThrows(RuntimeException.class, () -> catalog.exportNames(Set.of("a"), null));
		// ToolEntry 紧凑构造校验
		assertThrows(RuntimeException.class,
			() -> new ToolEntry(null, args -> "x", ToolMetadata.builder("a").build()));
		assertThrows(RuntimeException.class,
			() -> new ToolEntry(fn("a"), null, ToolMetadata.builder("a").build()));
		assertThrows(RuntimeException.class,
			() -> new ToolEntry(fn("a"), args -> "x", null));
	}

	@Test
	public void testConflictStrategyNullFallback() {
		ToolCatalog catalog = new ToolCatalog().conflictStrategy(null);
		catalog.register(fn("x"), args -> "a");
		// null 回退 OVERWRITE，不抛
		catalog.register(fn("x"), args -> "b");
		assertEquals(1, catalog.size());
	}
}
