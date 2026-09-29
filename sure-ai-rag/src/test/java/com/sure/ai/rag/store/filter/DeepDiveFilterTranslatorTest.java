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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;

/**
 * D2 三方言翻译器（Elasticsearch/OpenSearch/Redis）单元测试，零网络。
 *
 * @author sureai
 * @since 1.8.0
 */
public class DeepDiveFilterTranslatorTest {

	// ---- Elasticsearch ----

	/** ES：Eq → term。 */
	@Test
	public void testEsEq() {
		JsonObject out = new ElasticsearchFilterTranslator().translate(FilterExpression.eq("t", "acme"));
		assertEquals("acme", out.getJsonObject("term").getString("t"));
	}

	/** ES：And → bool.must 数组。 */
	@Test
	public void testEsAnd() {
		JsonObject out = new ElasticsearchFilterTranslator()
				.translate(FilterExpression.eq("a", 1).and(FilterExpression.gt("s", 0.5)));
		JsonArray must = out.getJsonObject("bool").getJsonArray("must");
		assertEquals(2, must.size());
		assertEquals(1, must.getJsonObject(0).getJsonObject("term").getInt("a"));
		assertEquals(0.5, must.getJsonObject(1).getJsonObject("range").getJsonObject("s").getDouble("gt"), 1e-9);
	}

	/** ES：Or → bool.should；Not → bool.must_not；In → terms。 */
	@Test
	public void testEsOrNotIn() {
		JsonObject out = new ElasticsearchFilterTranslator()
				.translate(FilterExpression.eq("a", 1).or(FilterExpression.eq("b", 2)));
		JsonArray should = out.getJsonObject("bool").getJsonArray("should");
		assertEquals(2, should.size());

		JsonObject notOut = new ElasticsearchFilterTranslator()
				.translate(FilterExpression.eq("x", "v").not());
		JsonArray mustNot = notOut.getJsonObject("bool").getJsonArray("must_not");
		assertEquals("v", mustNot.getJsonObject(0).getJsonObject("term").getString("x"));

		JsonObject inOut = new ElasticsearchFilterTranslator()
				.translate(FilterExpression.in("status", List.of("ok", "pending")));
		JsonArray values = inOut.getJsonObject("terms").getJsonArray("status");
		assertEquals(2, values.size());
	}

	/** ES：Ne → must_not term；range 四方向。 */
	@Test
	public void testEsNeAndRange() {
		JsonObject out = new ElasticsearchFilterTranslator().translate(FilterExpression.ne("c", "x"));
		assertEquals("x", out.getJsonObject("bool").getJsonArray("must_not")
				.getJsonObject(0).getJsonObject("term").getString("c"));

		JsonObject lt = new ElasticsearchFilterTranslator().translate(FilterExpression.lt("age", 18));
		assertEquals(18, lt.getJsonObject("range").getJsonObject("age").getInt("lt"));
		JsonObject lte = new ElasticsearchFilterTranslator().translate(FilterExpression.lte("age", 18));
		assertEquals(18, lte.getJsonObject("range").getJsonObject("age").getInt("lte"));
	}

	// ---- OpenSearch ----

	/** OpenSearch：与 ES 同构 bool，但独立类。 */
	@Test
	public void testOpenSearchBool() {
		JsonObject out = new OpenSearchFilterTranslator()
				.translate(FilterExpression.eq("cat", "books").and(FilterExpression.gte("p", 10)));
		JsonArray must = out.getJsonObject("bool").getJsonArray("must");
		assertEquals(2, must.size());
		assertEquals("books", must.getJsonObject(0).getJsonObject("term").getString("cat"));
		assertEquals(10, must.getJsonObject(1).getJsonObject("range").getJsonObject("p").getInt("gte"));
	}

	/** OpenSearch：Not → must_not。 */
	@Test
	public void testOpenSearchNot() {
		JsonObject out = new OpenSearchFilterTranslator()
				.translate(FilterExpression.eq("r", 5).not());
		assertEquals(5, out.getJsonObject("bool").getJsonArray("must_not")
				.getJsonObject(0).getJsonObject("term").getInt("r"));
	}

	// ---- Redis ----

	/** Redis：TAG eq → @field:{tag}。 */
	@Test
	public void testRedisTagEq() {
		RedisFilterTranslator t = new RedisFilterTranslator(Set.of());
		assertEquals("@tenant:{acme}", t.translate(FilterExpression.eq("tenant", "acme")));
	}

	/** Redis：NUMERIC eq → [v v]，范围开区间 ( 前缀。 */
	@Test
	public void testRedisNumericRange() {
		RedisFilterTranslator t = new RedisFilterTranslator(Set.of("score"));
		assertEquals("@score:[0.5 0.5]", t.translate(FilterExpression.eq("score", 0.5)));
		assertEquals("@score:[(1 +inf]", t.translate(FilterExpression.gt("score", 1)));
		assertEquals("@score:[-inf (10]", t.translate(FilterExpression.lt("score", 10)));
		assertEquals("@score:[-inf 10]", t.translate(FilterExpression.lte("score", 10)));
	}

	/** Redis：And 空格、Or |、Not -。 */
	@Test
	public void testRedisLogic() {
		RedisFilterTranslator t = new RedisFilterTranslator(Set.of());
		String and = t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")));
		assertTrue(and.contains(" ") && and.contains("(@a:{1})") && and.contains("(@b:{2})"));
		String or = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertTrue(or.contains("|"));
		String not = t.translate(FilterExpression.eq("a", "1").not());
		assertEquals("-(@a:{1})", not);
	}

	/** Redis：In TAG → {v1 | v2}。 */
	@Test
	public void testRedisInTag() {
		RedisFilterTranslator t = new RedisFilterTranslator(Set.of());
		String s = t.translate(FilterExpression.in("status", List.of("ok", "pending")));
		assertTrue(s.startsWith("@status:{") && s.contains("|"));
	}

	/** Redis：null → null。 */
	@Test
	public void testNull() {
		assertNull(new ElasticsearchFilterTranslator().translate(null));
		assertNull(new OpenSearchFilterTranslator().translate(null));
		assertNull(new RedisFilterTranslator(Set.of()).translate(null));
	}
}
