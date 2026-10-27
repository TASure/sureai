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

package com.sure.ai.client.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.List;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.sure.ai.internal.test.tag.Slow;
import com.sure.ai.model.ChatResponse;

/**
 * {@link LruCacheStore} 单元测试。
 *
 * <p>含 TTL 过期时序等待，标注为 {@link Slow}，在 {@code -Pfast} 构建中排除。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
@Category(Slow.class)
public class LruCacheStoreTest {

	/** 构造一个测试响应。 */
	private static ChatResponse resp(String id) {
		return ChatResponse.of(id, "gpt", List.of(), null, "{\"id\":\"" + id + "\"}");
	}

	/** 写入后读取返回相同对象。 */
	@Test
	public void testPutAndGet() {
		LruCacheStore store = new LruCacheStore();
		ChatResponse r = resp("c1");
		store.put("k1", r, 60_000);
		assertSame(r, store.get("k1"));
		assertEquals(1, store.size());
	}

	/** TTL 过期后 get 返回 null。 */
	@Test
	public void testTtlExpiry() throws InterruptedException {
		LruCacheStore store = new LruCacheStore(16, 50);
		store.put("k1", resp("c1"), 50);
		assertNotNull(store.get("k1"));
		waitUntilExpired(store, "k1");
		assertNull(store.get("k1"));
	}

	/** capacity=2 写入 3 条，最久未访问的被淘汰。 */
	@Test
	public void testCapacityEviction() {
		LruCacheStore store = new LruCacheStore(2, 60_000);
		store.put("a", resp("a"), 60_000);
		store.put("b", resp("b"), 60_000);
		store.put("c", resp("c"), 60_000);
		assertEquals(2, store.size());
		assertNull(store.get("a"));
		assertNotNull(store.get("b"));
		assertNotNull(store.get("c"));
	}

	/** 访问中间条目后，最旧的被淘汰而非中间的。 */
	@Test
	public void testLruOrder() {
		LruCacheStore store = new LruCacheStore(2, 60_000);
		store.put("a", resp("a"), 60_000);
		store.put("b", resp("b"), 60_000);
		// 访问 a，使其变为最近使用
		assertNotNull(store.get("a"));
		store.put("c", resp("c"), 60_000);
		assertNotNull(store.get("a"));
		assertNull(store.get("b"));
		assertNotNull(store.get("c"));
	}

	/** remove 删除单条，clear 清空。 */
	@Test
	public void testRemoveAndClear() {
		LruCacheStore store = new LruCacheStore(16, 60_000);
		store.put("a", resp("a"), 60_000);
		store.put("b", resp("b"), 60_000);
		assertEquals(2, store.size());
		store.remove("a");
		assertNull(store.get("a"));
		assertNotNull(store.get("b"));
		store.clear();
		assertEquals(0, store.size());
	}

	/** 过期条目在 get 时被惰性删除。 */
	@Test
	public void testExpiredEntryLazyCleanup() throws InterruptedException {
		LruCacheStore store = new LruCacheStore(16, 50);
		store.put("a", resp("a"), 50);
		waitUntilExpired(store, "a");
		// get 触发惰性删除
		assertNull(store.get("a"));
		assertEquals(0, store.size());
	}

	/** put 传 ttlMillis<=0 时使用 store 默认 TTL。 */
	@Test
	public void testDefaultTtlUsedWhenNonPositive() throws InterruptedException {
		LruCacheStore store = new LruCacheStore(16, 200);
		store.put("a", resp("a"), 0);
		assertNotNull(store.get("a"));
		waitUntilExpired(store, "a");
		assertNull(store.get("a"));
	}

	/** 轮询等待条目过期（替代固定 Thread.sleep，避免 CI 调度抖动导致 flaky）。 */
	private static void waitUntilExpired(LruCacheStore store, String key) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000L;
		while (store.get(key) != null && System.currentTimeMillis() < deadline) {
			Thread.sleep(10L);
		}
		assertNull(store.get(key));
	}
}
