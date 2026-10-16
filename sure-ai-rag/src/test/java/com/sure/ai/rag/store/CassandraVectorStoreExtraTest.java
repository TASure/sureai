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
package com.sure.ai.rag.store;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.cassandra.CassandraVectorStore;

/**
 * {@link CassandraVectorStore} 补覆盖测试：add(Vector) 直接调用、三参检索重载委托、
 * 多元素元数据 CQL map 字面量拼接、builder.timeout setter，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class CassandraVectorStoreExtraTest {

	private CassandraVectorStoreDeepTest.MockCassandra mock;

	/** 启动 mock。 */
	@Before
	public void setUp() throws java.io.IOException {
		this.mock = new CassandraVectorStoreDeepTest.MockCassandra();
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.mock.close();
	}

	private CassandraVectorStore.Builder baseBuilder() {
		return CassandraVectorStore.builder().host("127.0.0.1").port(mock.port())
				.keyspace("testks").table("docs");
	}

	/** 多元素元数据向量。 */
	private static Vector vMulti(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "text-" + id,
				Map.of("source", id + ".pdf", "author", "alice"));
	}

	/** add(Vector) 直接调用（经 Assert.notNull 后委托 addAll）。 */
	@Test
	public void testAddSingleVector() {
		baseBuilder().build().add(vMulti("v1"));
		assertTrue(mock.lastQuery().startsWith("INSERT"));
	}

	/** 三参检索重载委托。 */
	@Test
	public void testSearchThreeArgOverload() {
		assertNotNull(baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5));
	}

	/** builder.timeout setter。 */
	@Test
	public void testBuilderTimeout() {
		assertNotNull(baseBuilder().timeout(Duration.ofSeconds(3)).build());
	}
}
