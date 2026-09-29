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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link FilterExpression} 与三方言翻译器单元测试，零网络。
 *
 * @author sureai
 * @since 1.8.0
 */
public class FilterExpressionTest {

	/** 1. 表达式结构构造正确（Eq/And/Or/Not/In/Gt）。 */
	@Test
	public void testExpressionStructure() {
		Eq eq = FilterExpression.eq("tenant", "acme");
		assertEquals("tenant", eq.field());
		assertEquals("acme", eq.value());

		And and = eq.and(FilterExpression.gt("score", 0.8));
		assertEquals(2, and.conditions().size());
		assertTrue(and.conditions().get(1) instanceof Gt);

		Or or = FilterExpression.eq("a", 1).or(
				FilterExpression.in("b", List.of("x", "y")));
		assertEquals(2, or.conditions().size());
		assertTrue(or.conditions().get(1) instanceof In);

		Not not = FilterExpression.eq("x", 1).not();
		assertTrue(not.expression() instanceof Eq);

		In in = FilterExpression.in("status", List.of("ok", "pending"));
		assertEquals(2, in.values().size());
	}

	/** 2. Qdrant 翻译：must/key/match 关键字断言。 */
	@Test
	public void testQdrantTranslate() {
		JsonObject out = new QdrantFilterTranslator()
				.translate(FilterExpression.eq("city", "London"));
		assertTrue(out.has("must"));
		JsonArray must = out.getJsonArray("must");
		assertEquals(1, must.size());
		JsonObject cond = must.getJsonObject(0);
		assertEquals("city", cond.getString("key"));
		assertEquals("London", cond.getJsonObject("match").getString("value"));
		assertFalse(out.has("must_not"));
	}

	/** 2b. Qdrant 翻译：In → match.any，Gt → range.gt。 */
	@Test
	public void testQdrantInAndRange() {
		JsonObject out = new QdrantFilterTranslator()
				.translate(FilterExpression.in("tag", List.of("a", "b")));
		JsonObject cond = out.getJsonArray("must").getJsonObject(0);
		JsonArray any = cond.getJsonObject("match").getJsonArray("any");
		assertEquals(2, any.size());

		JsonObject range = new QdrantFilterTranslator()
				.translate(FilterExpression.gt("price", 100)).getJsonArray("must")
				.getJsonObject(0);
		assertEquals(100, range.getJsonObject("range").getInt("gt"));
	}

	/** 3. Pinecone 翻译：$and/$eq 关键字断言。 */
	@Test
	public void testPineconeTranslate() {
		FilterExpression f = FilterExpression.eq("genre", "documentary")
				.and(FilterExpression.gt("year", 2019));
		JsonObject out = new PineconeFilterTranslator().translate(f);
		assertTrue(out.has("$and"));
		JsonArray arr = out.getJsonArray("$and");
		assertEquals(2, arr.size());
		JsonObject first = arr.getJsonObject(0);
		assertEquals("documentary", first.getJsonObject("genre").getString("$eq"));
		JsonObject second = arr.getJsonObject(1);
		assertEquals(2019, second.getJsonObject("year").getInt("$gt"));
	}

	/** 3b. Pinecone Not 取反：Not(Eq) → $ne，Not(Gt) → $lte。 */
	@Test
	public void testPineconeNotNegation() {
		JsonObject out = new PineconeFilterTranslator()
				.translate(FilterExpression.eq("a", 1).not());
		assertEquals(1, out.getJsonObject("a").getInt("$ne"));

		JsonObject out2 = new PineconeFilterTranslator()
				.translate(FilterExpression.gt("age", 18).not());
		assertEquals(18, out2.getJsonObject("age").getInt("$lte"));
	}

	/** 4. Weaviate 翻译：operator/operands/valueText/valueInt 断言。 */
	@Test
	public void testWeaviateTranslate() {
		FilterExpression f = FilterExpression.eq("round", "Double")
				.and(FilterExpression.lt("points", 600));
		JsonObject out = new WeaviateFilterTranslator().translate(f);
		assertEquals("And", out.getString("operator"));
		JsonArray operands = out.getJsonArray("operands");
		assertEquals(2, operands.size());

		JsonObject eqOp = operands.getJsonObject(0);
		assertEquals("Equal", eqOp.getString("operator"));
		assertEquals("Double", eqOp.getString("valueText"));
		assertEquals("round", eqOp.getJsonArray("path").getString(0));

		JsonObject ltOp = operands.getJsonObject(1);
		assertEquals("LessThan", ltOp.getString("operator"));
		assertEquals(600, ltOp.getInt("valueInt"));
	}

	/** 4b. Weaviate Not → operator Not 单子 operands。 */
	@Test
	public void testWeaviateNot() {
		JsonObject out = new WeaviateFilterTranslator()
				.translate(FilterExpression.eq("answer", "X").not());
		assertEquals("Not", out.getString("operator"));
		assertEquals(1, out.getJsonArray("operands").size());
		assertEquals("Equal", out.getJsonArray("operands").getJsonObject(0)
				.getString("operator"));
	}

	/** 5. null filter → 各翻译器返回 null。 */
	@Test
	public void testNullFilterReturnsNull() {
		assertNull(new QdrantFilterTranslator().translate(null));
		assertNull(new PineconeFilterTranslator().translate(null));
		assertNull(new WeaviateFilterTranslator().translate(null));
	}

	/** 6. 嵌套逻辑：And(Or(Eq,Eq), Ne) → Qdrant must 嵌套 should + must_not。 */
	@Test
	public void testNestedAndOrNe() {
		FilterExpression f = FilterExpression.eq("a", 1)
				.or(FilterExpression.eq("b", 2))
				.and(FilterExpression.ne("c", "x"));
		JsonObject out = new QdrantFilterTranslator().translate(f);

		JsonArray must = out.getJsonArray("must");
		assertEquals(1, must.size());
		JsonObject nested = must.getJsonObject(0).getJsonObject("filter");
		JsonArray should = nested.getJsonArray("should");
		assertEquals(2, should.size());
		assertEquals("a", should.getJsonObject(0).getString("key"));
		assertEquals("b", should.getJsonObject(1).getString("key"));

		JsonArray mustNot = out.getJsonArray("must_not");
		assertEquals(1, mustNot.size());
		JsonObject neCond = mustNot.getJsonObject(0);
		assertEquals("c", neCond.getString("key"));
		assertEquals("x", neCond.getJsonObject("match").getString("value"));
	}
}
