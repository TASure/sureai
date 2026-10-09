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

package com.sure.ai.framework.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.cache.CacheStore;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Role;
import org.junit.Test;

/**
 * {@link SemanticCache} 默认实现的零网络单元测试。
 *
 * <p>使用确定性的 {@link FakeEmbedder}：相同主题向量一致、低相似向量余弦约 0.5、
 * 无关向量正交（余弦 0），从而精确控制阈值命中边界。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public class SemanticCacheTest {

	/** 主题 A 向量（一维主导）。 */
	private static final float[] V_A = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
	/** 与 A 同方向的改写向量：余弦 1.0。 */
	private static final float[] V_PARA = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
	/** 与 A 夹角 60°：余弦 0.5（低于默认 0.85，高于 0.4）。 */
	private static final float[] V_LOW = {0.5f, 0.866f, 0f, 0f, 0f, 0f, 0f, 0f};
	/** 与 A 正交：余弦 0。 */
	private static final float[] V_UNREL = {0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f};

	private static final String Q = "怎么给猫退烧";
	private static final String Q_PARA = "猫咪发烧该怎么处理";
	private static final String Q_LOW = "猫喜欢吃什么";
	private static final String Q_UNREL = "今天股市涨了吗";

	private static ChatResponse response(String content) {
		ChatMessage msg = ChatMessage.of(Role.ASSISTANT, content, null, null, null, null);
		Choice choice = Choice.of(0, msg, "stop");
		String raw = "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
		return ChatResponse.of("c1", "m", List.of(choice), null, raw);
	}

	private static FakeEmbedder embedder() {
		FakeEmbedder e = new FakeEmbedder();
		e.table.put(Q, V_A);
		e.table.put(Q_PARA, V_PARA);
		e.table.put(Q_LOW, V_LOW);
		e.table.put(Q_UNREL, V_UNREL);
		return e;
	}

	@Test
	public void thresholdHitReturnsCachedResponse() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		ChatResponse resp = response("退热贴");
		cache.put(Q, resp, 60_000);
		ChatResponse hit = cache.get(Q_PARA);
		assertNotNull("同义改写应命中语义缓存", hit);
		assertEquals("退热贴", hit.choices().get(0).message().content());
	}

	@Test
	public void secondHitAfterBackfill() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		cache.put(Q, response("退热贴"), 60_000);
		assertNotNull(cache.get(Q_PARA));
		assertNotNull("二次命中应稳定", cache.get(Q_PARA));
		assertEquals(1, ((DefaultSemanticCache) cache).size());
	}

	@Test
	public void belowThresholdMiss() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		cache.put(Q, response("退热贴"), 60_000);
		assertNull("余弦 0.5 < 0.85 应 miss", cache.get(Q_LOW));
		assertNull("正交向量应 miss", cache.get(Q_UNREL));
	}

	@Test
	public void loweredThresholdTurnsLowSimilarityIntoHit() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).threshold(0.40).build();
		cache.put(Q, response("退热贴"), 60_000);
		assertNotNull("阈值降到 0.4 后余弦 0.5 应命中", cache.get(Q_LOW));
	}

	@Test
	public void expiredEntryMissesAndIsLazyRemoved() throws Exception {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		cache.put(Q, response("退热贴"), 30);
		Thread.sleep(150);
		assertNull("TTL 过期后应 miss", cache.get(Q_PARA));
		assertEquals("过期条目应在扫描时惰性剔除", 0, ((DefaultSemanticCache) cache).size());
	}

	@Test
	public void removeDropsEntry() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		cache.put(Q, response("退热贴"), 60_000);
		cache.remove(Q);
		assertNull(cache.get(Q_PARA));
	}

	@Test
	public void clearDropsAll() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		cache.put(Q, response("退热贴"), 60_000);
		cache.put(Q_UNREL, response("行情"), 60_000);
		assertEquals(2, ((DefaultSemanticCache) cache).size());
		cache.clear();
		assertEquals(0, ((DefaultSemanticCache) cache).size());
		assertNull(cache.get(Q_PARA));
	}

	@Test
	public void blankQueryAndEmptyEmbeddingAreMiss() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		assertNull(cache.get("   "));
		assertNull(cache.get(null));
	}

	@Test
	public void putWithEmptyEmbeddingRejected() {
		EmbeddingClient empty = req -> EmbeddingResponse.of("e", List.of(), null);
		SemanticCache cache = SemanticCache.builder().embedder(empty).build();
		assertThrows(RuntimeException.class, () -> cache.put(Q, response("x"), 60_000));
	}

	@Test
	public void differentEmbedderIsPluggedIndependently() {
		FakeEmbedder a = embedder();
		FakeEmbedder b = embedder();
		SemanticCache ca = SemanticCache.builder().embedder(a).build();
		SemanticCache cb = SemanticCache.builder().embedder(b).build();
		ca.put(Q, response("退热贴"), 60_000);
		assertNotNull(ca.get(Q_PARA));
		assertNull("不同 embedder 实例互不影响", cb.get(Q_PARA));
	}

	@Test
	public void customCacheStoreBackendReceivesPayloadAndServesHits() {
		RecordingStore store = new RecordingStore();
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).store(store).build();
		cache.put(Q, response("退热贴"), 60_000);
		assertEquals("载荷应按 entryId 写入后端", 1, store.putKeys.size());
		ChatResponse hit = cache.get(Q_PARA);
		assertNotNull("经自定义后端命中", hit);
		assertEquals("退热贴", hit.choices().get(0).message().content());
	}

	@Test
	public void payloadMissingInStoreRemovesIndexEntry() {
		CacheStore noPayload = new CacheStore() {
			@Override public ChatResponse get(String key) { return null; }
			@Override public void put(String key, ChatResponse response, long ttlMillis) { }
			@Override public void remove(String key) { }
			@Override public void clear() { }
		};
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).store(noPayload).build();
		cache.put(Q, response("退热贴"), 60_000);
		assertEquals(1, ((DefaultSemanticCache) cache).size());
		assertNull("后端无载荷时应 miss", cache.get(Q_PARA));
		assertEquals("索引条目应同步剔除", 0, ((DefaultSemanticCache) cache).size());
	}

	@Test
	public void defaultInMemoryBackendIsUsableWithoutCustomStore() {
		SemanticCache cache = SemanticCache.builder().embedder(embedder()).build();
		cache.put(Q, response("退热贴"), 60_000);
		assertSame("缺省后端命中即返回载荷对象", "退热贴",
			cache.get(Q_PARA).choices().get(0).message().content());
	}

	/** 确定性向量客户端：按查询原文查表命中预置向量，否则返回正交向量。 */
	static final class FakeEmbedder implements EmbeddingClient {
		final Map<String, float[]> table = new HashMap<>();

		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			float[] v = table.getOrDefault(request.input().get(0), V_UNREL);
			return EmbeddingResponse.of("fake", List.of(v), null);
		}
	}

	/** 记录写入键的后端，用于验证载荷落库形态。 */
	static final class RecordingStore implements CacheStore {
		final List<String> putKeys = new ArrayList<>();
		private final Map<String, ChatResponse> data = new HashMap<>();

		@Override public ChatResponse get(String key) { return data.get(key); }

		@Override public void put(String key, ChatResponse response, long ttlMillis) {
			putKeys.add(key);
			data.put(key, response);
		}

		@Override public void remove(String key) { data.remove(key); }

		@Override public void clear() { data.clear(); }
	}
}
