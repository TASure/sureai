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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sure.ai.model.ToolFunction;

/**
 * {@link ToolCatalog} 并发注册 / 发现压测（线程池，零网络）。
 */
public class ToolCatalogConcurrencyTest {

	@Test
	public void concurrentRegisterUniqueNames() throws Exception {
		ToolCatalog catalog = new ToolCatalog();
		int threads = 16;
		int perThread = 200;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			final int base = t;
			futures.add(pool.submit(() -> {
				ready.countDown();
				ready.await();
				for (int i = 0; i < perThread; i++) {
					String name = "t" + base + "_" + i;
					catalog.register(ToolFunction.of(name, "d" + i, "{}"), args -> name);
				}
				return null;
			}));
		}
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();
		assertEquals(threads * perThread, catalog.size());
	}

	@Test
	public void concurrentRegisterRenameAndDiscovery() throws Exception {
		ToolCatalog catalog = new ToolCatalog().conflictStrategy(ConflictStrategy.RENAME);
		int threads = 8;
		CountDownLatch ready = new CountDownLatch(threads);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				ready.await();
				for (int i = 0; i < 100; i++) {
					catalog.register(ToolFunction.of("same", "d", "{}"), args -> "x");
				}
				return null;
			}));
			futures.add(pool.submit(() -> {
				ready.countDown();
				ready.await();
				for (int i = 0; i < 100; i++) {
					catalog.list();
					catalog.findByKeyword("d");
					catalog.filterBySource(ToolSource.LOCAL);
				}
				return null;
			}));
		}
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();
		assertEquals(800, catalog.size());
		assertTrue(catalog.get("same").isPresent());
	}
}
