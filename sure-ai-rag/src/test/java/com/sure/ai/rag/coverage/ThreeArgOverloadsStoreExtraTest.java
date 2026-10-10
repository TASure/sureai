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
package com.sure.ai.rag.coverage;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.store.RedisVectorStore;
import com.sure.ai.rag.store.mongodb.MongoDbVectorStore;
import com.sure.ai.rag.store.pgvector.PgVectorStore;

/**
 * 3参 similaritySearch(emb, topK, minScore) 重载补覆盖（Pg/Mongo/Redis）。
 *
 * @author sureai
 * @since 2.6.0
 */
public class ThreeArgOverloadsStoreExtraTest {

	private static final float[] EMB = new float[] { 0.1f, 0.2f, 0.3f };

	/** PgVector：3参重载。 */
	@Test
	public void testPgThreeArg() {
		PgVectorStore store = PgVectorStore.builder()
				.host("127.0.0.1").port(1).database("db").user("u").table("t").build();
		assertThrows(AiException.class, () -> store.similaritySearch(EMB, 5, 0.5));
	}

	/** MongoDb：3参重载。 */
	@Test
	public void testMongoThreeArg() {
		MongoDbVectorStore store = MongoDbVectorStore.builder()
				.host("127.0.0.1").port(1).database("db").collection("c").vectorIndex("vi").build();
		assertThrows(AiException.class, () -> store.similaritySearch(EMB, 5, 0.5));
	}

	/** Redis：3参重载。 */
	@Test
	public void testRedisThreeArg() {
		RedisVectorStore store = RedisVectorStore.builder()
				.host("127.0.0.1").port(1).indexName("idx").dimension(3).build();
		assertThrows(AiException.class, () -> store.similaritySearch(EMB, 5, 0.5));
	}
}
