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
package com.sure.ai.rag.store.filter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Proxy;

import org.junit.Test;

/**
 * 过滤翻译器补覆盖测试：动态代理构造「未知」{@link FilterExpression} 触发各方言
 * 不支持条件异常分支；Qdrant 嵌套 And/Or 组合包装分支，零网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class FilterTranslatorExtraTest {

	/** 构造一个不属于 sealed permits 的 FilterExpression 代理。 */
	private static FilterExpression unknownExpr() {
		return (FilterExpression) Proxy.newProxyInstance(
				FilterExpression.class.getClassLoader(),
				new Class<?>[] { FilterExpression.class },
				(proxy, method, args) -> {
					String name = method.getName();
					if ("getClass".equals(name)) {
						return com.sure.ai.rag.store.filter.FilterExpression.class;
					}
					if ("equals".equals(name)) {
						return proxy == args[0];
					}
					if ("hashCode".equals(name)) {
						return System.identityHashCode(proxy);
					}
					if ("toString".equals(name)) {
						return "UnknownFilterExpression";
					}
					throw new UnsupportedOperationException("未知条件: " + name);
				});
	}

	/** Qdrant：未知条件 → IllegalArgumentException。 */
	@Test
	public void testQdrantUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new QdrantFilterTranslator().translate(unknownExpr()));
	}

	/** Weaviate：未知条件 → IllegalArgumentException。 */
	@Test
	public void testWeaviateUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new WeaviateFilterTranslator().translate(unknownExpr()));
	}

	/** Redis：未知条件 → IllegalArgumentException。 */
	@Test
	public void testRedisUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new RedisFilterTranslator(java.util.Set.of()).translate(unknownExpr()));
	}

	/** Pinecone：未知条件 → IllegalArgumentException。 */
	@Test
	public void testPineconeUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new PineconeFilterTranslator().translate(unknownExpr()));
	}

	/** PgVector：未知条件 → IllegalArgumentException。 */
	@Test
	public void testPgUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new PgVectorFilterTranslator().translate(unknownExpr()));
	}

	/** OpenSearch：未知条件 → IllegalArgumentException。 */
	@Test
	public void testOpenSearchUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new OpenSearchFilterTranslator().translate(unknownExpr()));
	}

	/** Elasticsearch：未知条件 → IllegalArgumentException。 */
	@Test
	public void testElasticsearchUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new ElasticsearchFilterTranslator().translate(unknownExpr()));
	}

	/** MongoDb：未知条件 → IllegalArgumentException。 */
	@Test
	public void testMongoDbUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new MongoDbFilterTranslator().translate(unknownExpr()));
	}

	/** Neo4j：未知条件 → IllegalArgumentException。 */
	@Test
	public void testNeo4jUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new Neo4jFilterTranslator().translate(unknownExpr()));
	}

	/** Cassandra：未知条件 → IllegalArgumentException。 */
	@Test
	public void testCassandraUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new CassandraFilterTranslator().translate(unknownExpr()));
	}

	/** Typesense：未知条件 → IllegalArgumentException。 */
	@Test
	public void testTypesenseUnsupported() {
		assertThrows(IllegalArgumentException.class,
				() -> new TypesenseFilterTranslator().translate(unknownExpr()));
	}

	/** Qdrant：Or 内含 And 嵌套 → 包装 filter 分支。 */
	@Test
	public void testQdrantNestedAndInOr() {
		FilterExpression nested = FilterExpression.eq("a", 1)
				.and(FilterExpression.gt("b", 2))
				.or(FilterExpression.eq("c", "x"));
		assertNotNull(new QdrantFilterTranslator().translate(nested));
	}
}
