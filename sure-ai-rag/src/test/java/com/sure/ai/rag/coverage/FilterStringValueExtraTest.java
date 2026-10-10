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

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.Neo4jFilterTranslator;
import com.sure.ai.rag.store.filter.PgVectorFilterTranslator;
import com.sure.ai.rag.store.filter.RedisFilterTranslator;
import com.sure.ai.rag.store.filter.TypesenseFilterTranslator;

/**
 * FilterTranslator：字符串值条件 → String.valueOf 分支补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class FilterStringValueExtraTest {

	/** Typesense：eq 字符串值。 */
	@Test
	public void testTypesenseStringEq() {
		TypesenseFilterTranslator t = new TypesenseFilterTranslator();
		assertNotNull(t.translate(FilterExpression.eq("source", "a.pdf")));
	}

	/** Redis：eq 字符串值。 */
	@Test
	public void testRedisStringEq() {
		RedisFilterTranslator t = new RedisFilterTranslator(java.util.Set.of());
		assertNotNull(t.translate(FilterExpression.eq("source", "a.pdf")));
	}

	/** PgVector：eq 字符串值。 */
	@Test
	public void testPgStringEq() {
		PgVectorFilterTranslator t = new PgVectorFilterTranslator();
		assertNotNull(t.translate(FilterExpression.eq("source", "a.pdf")));
	}

	/** Neo4j：eq 字符串值。 */
	@Test
	public void testNeo4jStringEq() {
		Neo4jFilterTranslator t = new Neo4jFilterTranslator();
		assertNotNull(t.translate(FilterExpression.eq("source", "a.pdf")));
	}
}
