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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.store.mongodb.Bson;

/**
 * v2.6.0 新增 5 方言翻译器（PgVector/Cassandra/MongoDb/Neo4j/Typesense）与既有
 * Pinecone/Qdrant 翻译器的全分支单元测试：每个叶子操作符、逻辑组合、null 入参、
 * 字面量转义与不支持操作符的异常路径，零网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class DialectFilterTranslatorDeepTest {

	// ===================== PgVector（SQL WHERE 片段） =====================

	/** null 入参返回 null。 */
	@Test
	public void testPgNull() {
		assertNull(new PgVectorFilterTranslator().translate(null));
	}

	/** Eq：字符串/数值/布尔三种字面量。 */
	@Test
	public void testPgEq() {
		PgVectorFilterTranslator t = new PgVectorFilterTranslator();
		assertEquals("metadata->>'t' = 'acme'", t.translate(FilterExpression.eq("t", "acme")));
		assertEquals("metadata->>'n' = 5", t.translate(FilterExpression.eq("n", 5)));
		assertEquals("metadata->>'f' = 'true'", t.translate(FilterExpression.eq("f", Boolean.TRUE)));
	}

	/** Ne / In 多值。 */
	@Test
	public void testPgNeIn() {
		PgVectorFilterTranslator t = new PgVectorFilterTranslator();
		assertEquals("metadata->>'s' <> 'x'", t.translate(FilterExpression.ne("s", "x")));
		assertEquals("metadata->>'st' IN ('ok','pending')",
				t.translate(FilterExpression.in("st", List.of("ok", "pending"))));
	}

	/** 范围：整数取整字面量，小数保留 double。 */
	@Test
	public void testPgRange() {
		PgVectorFilterTranslator t = new PgVectorFilterTranslator();
		assertEquals("(metadata->>'score')::float > 1.5", t.translate(FilterExpression.gt("score", 1.5)));
		assertEquals("(metadata->>'age')::float >= 18", t.translate(FilterExpression.gte("age", 18)));
		assertEquals("(metadata->>'age')::float < 18", t.translate(FilterExpression.lt("age", 18)));
		assertEquals("(metadata->>'age')::float <= 18", t.translate(FilterExpression.lte("age", 18)));
	}

	/** And/Or 子句加括号；Not 包 NOT(...)。 */
	@Test
	public void testPgLogic() {
		PgVectorFilterTranslator t = new PgVectorFilterTranslator();
		String and = t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")));
		assertEquals("(metadata->>'a' = '1') AND (metadata->>'b' = '2')", and);
		String or = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertTrue(or, or.contains(" OR "));
		assertEquals("NOT (metadata->>'a' = '1')", t.translate(FilterExpression.eq("a", "1").not()));
	}

	/** 单引号翻倍转义。 */
	@Test
	public void testPgEscape() {
		PgVectorFilterTranslator t = new PgVectorFilterTranslator();
		assertEquals("metadata->>'q' = 'o''brien'", t.translate(FilterExpression.eq("q", "o'brien")));
	}

	// ===================== Cassandra（CQL WHERE 片段） =====================

	/** null 入参返回 null。 */
	@Test
	public void testCassNull() {
		assertNull(new CassandraFilterTranslator().translate(null));
	}

	/** Eq：字符串/数值/布尔。 */
	@Test
	public void testCassEq() {
		CassandraFilterTranslator t = new CassandraFilterTranslator();
		assertEquals("metadata['t'] = 'acme'", t.translate(FilterExpression.eq("t", "acme")));
		assertEquals("metadata['n'] = 5.0", t.translate(FilterExpression.eq("n", 5)));
		assertEquals("metadata['f'] = true", t.translate(FilterExpression.eq("f", Boolean.TRUE)));
	}

	/** In 多值 + And/Or。 */
	@Test
	public void testCassInLogic() {
		CassandraFilterTranslator t = new CassandraFilterTranslator();
		assertEquals("metadata['st'] IN ('ok','pending')",
				t.translate(FilterExpression.in("st", List.of("ok", "pending"))));
		String and = t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")));
		assertEquals("(metadata['a'] = '1') AND (metadata['b'] = '2')", and);
		String or = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertTrue(or, or.contains(" OR "));
	}

	/** CQL WHERE 不支持 Ne/Not/范围，一律抛 IAE。 */
	@Test
	public void testCassUnsupportedThrows() {
		CassandraFilterTranslator t = new CassandraFilterTranslator();
		assertThrows(IllegalArgumentException.class, () -> t.translate(FilterExpression.ne("a", "1")));
		assertThrows(IllegalArgumentException.class, () -> t.translate(FilterExpression.eq("a", "1").not()));
		assertThrows(IllegalArgumentException.class, () -> t.translate(FilterExpression.gt("a", 1)));
		assertThrows(IllegalArgumentException.class, () -> t.translate(FilterExpression.gte("a", 1)));
		assertThrows(IllegalArgumentException.class, () -> t.translate(FilterExpression.lt("a", 1)));
		assertThrows(IllegalArgumentException.class, () -> t.translate(FilterExpression.lte("a", 1)));
	}

	/** 单引号翻倍转义。 */
	@Test
	public void testCassEscape() {
		CassandraFilterTranslator t = new CassandraFilterTranslator();
		assertEquals("metadata['q'] = 'a''b'", t.translate(FilterExpression.eq("q", "a'b")));
	}

	// ===================== MongoDB（BSON 谓词文档） =====================

	/** null 入参返回 null。 */
	@Test
	public void testMongoNull() {
		assertNull(new MongoDbFilterTranslator().translate(null));
	}

	/** Eq：字符串/数值/布尔映射为 BSON string/double/boolean。 */
	@Test
	public void testMongoEq() {
		MongoDbFilterTranslator t = new MongoDbFilterTranslator();
		Map<String, Object> m = Bson.parse(t.translate(FilterExpression.eq("t", "acme")).encode());
		assertEquals("acme", m.get("t"));
		Map<String, Object> mn = Bson.parse(t.translate(FilterExpression.eq("n", 5)).encode());
		assertEquals(5.0, (Double) mn.get("n"), 1e-9);
		Map<String, Object> mb = Bson.parse(t.translate(FilterExpression.eq("f", Boolean.TRUE)).encode());
		assertEquals(Boolean.TRUE, mb.get("f"));
	}

	/** Ne / In / 范围。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testMongoNeInRange() {
		MongoDbFilterTranslator t = new MongoDbFilterTranslator();
		Map<String, Object> ne = Bson.parse(t.translate(FilterExpression.ne("s", "x")).encode());
		Map<String, Object> neOp = (Map<String, Object>) ne.get("s");
		assertEquals("x", neOp.get("$ne"));

		Map<String, Object> in = Bson.parse(t.translate(FilterExpression.in("st", List.of("a", "b"))).encode());
		Map<String, Object> inOp = (Map<String, Object>) in.get("st");
		List<Object> arr = (List<Object>) inOp.get("$in");
		assertEquals(2, arr.size());

		Map<String, Object> gt = Bson.parse(t.translate(FilterExpression.gt("score", 1.5)).encode());
		Map<String, Object> gtOp = (Map<String, Object>) gt.get("score");
		assertEquals(1.5, (Double) gtOp.get("$gt"), 1e-9);
		Map<String, Object> gte = Bson.parse(t.translate(FilterExpression.gte("age", 18)).encode());
		assertEquals(18.0, (Double) ((Map<String, Object>) gte.get("age")).get("$gte"), 1e-9);
		Map<String, Object> lt = Bson.parse(t.translate(FilterExpression.lt("age", 18)).encode());
		assertEquals(18.0, (Double) ((Map<String, Object>) lt.get("age")).get("$lt"), 1e-9);
		Map<String, Object> lte = Bson.parse(t.translate(FilterExpression.lte("age", 18)).encode());
		assertEquals(18.0, (Double) ((Map<String, Object>) lte.get("age")).get("$lte"), 1e-9);
	}

	/** And/Or/Not → $and/$or/$not。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testMongoLogic() {
		MongoDbFilterTranslator t = new MongoDbFilterTranslator();
		Map<String, Object> and = Bson.parse(
				t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2"))).encode());
		List<Object> andArr = (List<Object>) and.get("$and");
		assertEquals(2, andArr.size());

		Map<String, Object> or = Bson.parse(
				t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2"))).encode());
		List<Object> orArr = (List<Object>) or.get("$or");
		assertEquals(2, orArr.size());

		Map<String, Object> not = Bson.parse(t.translate(FilterExpression.eq("a", "1").not()).encode());
		assertTrue(not.containsKey("$not"));
	}

	// ===================== Neo4j（Cypher WHERE 片段） =====================

	/** null 入参返回 null。 */
	@Test
	public void testNeoNull() {
		assertNull(new Neo4jFilterTranslator().translate(null));
	}

	/** Eq/Ne/In/范围/逻辑全覆盖。 */
	@Test
	public void testNeoAll() {
		Neo4jFilterTranslator t = new Neo4jFilterTranslator();
		assertEquals("n.t = 'acme'", t.translate(FilterExpression.eq("t", "acme")));
		assertEquals("n.n = 5", t.translate(FilterExpression.eq("n", 5)));
		assertEquals("n.f = true", t.translate(FilterExpression.eq("f", Boolean.TRUE)));
		assertEquals("n.s <> 'x'", t.translate(FilterExpression.ne("s", "x")));
		assertEquals("n.st IN ['ok','pending']", t.translate(FilterExpression.in("st", List.of("ok", "pending"))));
		assertEquals("n.score > 1.5", t.translate(FilterExpression.gt("score", 1.5)));
		assertEquals("n.age >= 18", t.translate(FilterExpression.gte("age", 18)));
		assertEquals("n.age < 18", t.translate(FilterExpression.lt("age", 18)));
		assertEquals("n.age <= 18", t.translate(FilterExpression.lte("age", 18)));
		String and = t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")));
		assertEquals("(n.a = '1') AND (n.b = '2')", and);
		String or = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertTrue(or, or.contains(" OR "));
		assertEquals("NOT (n.a = '1')", t.translate(FilterExpression.eq("a", "1").not()));
		assertEquals("n.q = 'a''b'", t.translate(FilterExpression.eq("q", "a'b")));
	}

	// ===================== Typesense（filter_by） =====================

	/** null 入参返回 null。 */
	@Test
	public void testTsNull() {
		assertNull(new TypesenseFilterTranslator().translate(null));
	}

	/** Eq/Ne/In/范围/逻辑全覆盖，字符串双引号包裹。 */
	@Test
	public void testTsAll() {
		TypesenseFilterTranslator t = new TypesenseFilterTranslator();
		assertEquals("t:=\"acme\"", t.translate(FilterExpression.eq("t", "acme")));
		assertEquals("n:=5", t.translate(FilterExpression.eq("n", 5)));
		assertEquals("f:=true", t.translate(FilterExpression.eq("f", Boolean.TRUE)));
		assertEquals("s:!=\"x\"", t.translate(FilterExpression.ne("s", "x")));
		assertEquals("st:[\"ok\",\"pending\"]", t.translate(FilterExpression.in("st", List.of("ok", "pending"))));
		assertEquals("score:>1.5", t.translate(FilterExpression.gt("score", 1.5)));
		assertEquals("age:>=18", t.translate(FilterExpression.gte("age", 18)));
		assertEquals("age:<18", t.translate(FilterExpression.lt("age", 18)));
		assertEquals("age:<=18", t.translate(FilterExpression.lte("age", 18)));
		String and = t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")));
		assertEquals("(a:=\"1\") && (b:=\"2\")", and);
		String or = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertTrue(or, or.contains(" || "));
		assertEquals("NOT (a:=\"1\")", t.translate(FilterExpression.eq("a", "1").not()));
		assertEquals("q:=\"a\"\"b\"", t.translate(FilterExpression.eq("q", "a\"b")));
	}

	// ===================== Pinecone（metadata filter，Not 德摩根） =====================

	/** 叶子操作符 + null。 */
	@Test
	public void testPineconeLeaves() {
		PineconeFilterTranslator t = new PineconeFilterTranslator();
		assertNull(t.translate(null));
		JsonObject eq = t.translate(FilterExpression.eq("t", "acme"));
		assertEquals("acme", eq.getJsonObject("t").getString("$eq"));
		assertEquals("x", t.translate(FilterExpression.ne("s", "x")).getJsonObject("s").getString("$ne"));
		assertEquals(2, t.translate(FilterExpression.in("st", List.of("a", "b")))
				.getJsonObject("st").getJsonArray("$in").size());
		assertEquals(1.5, t.translate(FilterExpression.gt("score", 1.5)).getJsonObject("score").getDouble("$gt"), 1e-9);
		assertEquals(18, t.translate(FilterExpression.gte("age", 18)).getJsonObject("age").getInt("$gte"));
		assertEquals(18, t.translate(FilterExpression.lt("age", 18)).getJsonObject("age").getInt("$lt"));
		assertEquals(18, t.translate(FilterExpression.lte("age", 18)).getJsonObject("age").getInt("$lte"));
	}

	/** Not 德摩根映射。 */
	@Test
	public void testPineconeNot() {
		PineconeFilterTranslator t = new PineconeFilterTranslator();
		assertEquals("x", t.translate(FilterExpression.eq("s", "x").not()).getJsonObject("s").getString("$ne"));
		assertEquals("x", t.translate(FilterExpression.ne("s", "x").not()).getJsonObject("s").getString("$eq"));
		assertEquals(2, t.translate(FilterExpression.in("st", List.of("a", "b")).not())
				.getJsonObject("st").getJsonArray("$nin").size());
		assertEquals(1.5, t.translate(FilterExpression.gt("score", 1.5).not())
				.getJsonObject("score").getDouble("$lte"), 1e-9);
		assertEquals(18, t.translate(FilterExpression.gte("age", 18).not()).getJsonObject("age").getInt("$lt"));
		assertEquals(18, t.translate(FilterExpression.lt("age", 18).not()).getJsonObject("age").getInt("$gte"));
		assertEquals(18, t.translate(FilterExpression.lte("age", 18).not()).getJsonObject("age").getInt("$gt"));
	}

	/** Not(And) → $or，Not(Or) → $and，Not(Not) 双层消除。 */
	@Test
	public void testPineconeNotLogic() {
		PineconeFilterTranslator t = new PineconeFilterTranslator();
		JsonObject notAnd = t.translate(
				FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")).not());
		assertEquals(2, notAnd.getJsonArray("$or").size());
		JsonObject notOr = t.translate(
				FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")).not());
		assertEquals(2, notOr.getJsonArray("$and").size());
		JsonObject notNot = t.translate(FilterExpression.eq("a", "1").not().not());
		assertEquals("1", notNot.getJsonObject("a").getString("$eq"));
	}

	/** And/Or 根节点。 */
	@Test
	public void testPineconeLogic() {
		PineconeFilterTranslator t = new PineconeFilterTranslator();
		JsonObject and = t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")));
		assertEquals(2, and.getJsonArray("$and").size());
		JsonObject or = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertEquals(2, or.getJsonArray("$or").size());
	}

	// ===================== Qdrant（三桶 filter） =====================

	/** null / Eq / In / 范围。 */
	@Test
	public void testQdrantLeaves() {
		QdrantFilterTranslator t = new QdrantFilterTranslator();
		assertNull(t.translate(null));
		JsonObject eq = t.translate(FilterExpression.eq("t", "acme"));
		JsonObject must = eq.getJsonArray("must").getJsonObject(0);
		assertEquals("t", must.getString("key"));
		assertEquals("acme", must.getJsonObject("match").getString("value"));

		JsonObject in = t.translate(FilterExpression.in("st", List.of("a", "b")));
		assertEquals(2, in.getJsonArray("must").getJsonObject(0).getJsonObject("match").getJsonArray("any").size());

		JsonObject gt = t.translate(FilterExpression.gt("score", 1.5));
		assertEquals(1.5, gt.getJsonArray("must").getJsonObject(0).getJsonObject("range").getDouble("gt"), 1e-9);
		JsonObject gte = t.translate(FilterExpression.gte("age", 18));
		assertEquals(18, gte.getJsonArray("must").getJsonObject(0).getJsonObject("range").getInt("gte"));
		JsonObject lt = t.translate(FilterExpression.lt("age", 18));
		assertEquals(18, lt.getJsonArray("must").getJsonObject(0).getJsonObject("range").getInt("lt"));
		JsonObject lte = t.translate(FilterExpression.lte("age", 18));
		assertEquals(18, lte.getJsonArray("must").getJsonObject(0).getJsonObject("range").getInt("lte"));
	}

	/** Ne → must_not；Not 单子项直搬 vs 复合包 filter。 */
	@Test
	public void testQdrantNeNot() {
		QdrantFilterTranslator t = new QdrantFilterTranslator();
		JsonObject ne = t.translate(FilterExpression.ne("s", "x"));
		assertEquals("x", ne.getJsonArray("must_not").getJsonObject(0).getJsonObject("match").getString("value"));

		JsonObject notLeaf = t.translate(FilterExpression.eq("a", "1").not());
		// 单子项 must → 直搬 must_not
		JsonObject moved = notLeaf.getJsonArray("must_not").getJsonObject(0);
		assertEquals("a", moved.getString("key"));
		assertEquals("1", moved.getJsonObject("match").getString("value"));

		JsonObject notCompound = t.translate(
				FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")).not());
		// 子 filter 为 should（无 must 桶）→ 包 filter
		assertTrue(notCompound.getJsonArray("must_not").getJsonObject(0).has("filter"));
	}

	/** Or 根节点 → should；Or 嵌套在 And 中 → must 嵌套 filter。 */
	@Test
	public void testQdrantOr() {
		QdrantFilterTranslator t = new QdrantFilterTranslator();
		JsonObject orRoot = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertEquals(2, orRoot.getJsonArray("should").size());

		JsonObject nested = t.translate(
				FilterExpression.eq("t", "x").and(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2"))));
		JsonArray must = nested.getJsonArray("must");
		assertEquals(2, must.size());
		assertTrue(must.getJsonObject(1).has("filter"));
	}

	/** Ne / Not 嵌套在 And 中 → toNestedCondition 包装分支。 */
	@Test
	public void testQdrantNestedNeAndNot() {
		QdrantFilterTranslator t = new QdrantFilterTranslator();
		JsonObject andNe = t.translate(
				FilterExpression.eq("t", "x").and(FilterExpression.ne("s", "v")));
		assertEquals(1, andNe.getJsonArray("must").size());
		assertEquals(1, andNe.getJsonArray("must_not").size());

		JsonObject andNot = t.translate(
				FilterExpression.eq("t", "x").and(FilterExpression.eq("a", "1").not()));
		assertEquals(1, andNot.getJsonArray("must").size());
		assertEquals(1, andNot.getJsonArray("must_not").size());

		// Or 内嵌 Ne → toNestedCondition Ne 包装分支
		JsonObject orNe = t.translate(
				FilterExpression.eq("t", "x").and(FilterExpression.eq("a", "1").or(FilterExpression.ne("s", "v"))));
		assertEquals(2, orNe.getJsonArray("must").size());
		assertTrue(orNe.getJsonArray("must").getJsonObject(1).has("filter"));
	}

	// ===================== Redis（RediSearch 查询子串） =====================

	/** null 入参；Eq TAG / NUMERIC；In TAG / NUMERIC；范围。 */
	@Test
	public void testRedisLeaves() {
		RedisFilterTranslator t = new RedisFilterTranslator(java.util.Set.of("age", "score"));
		assertNull(t.translate(null));
		assertEquals("@name:{alice}", t.translate(FilterExpression.eq("name", "alice")));
		assertEquals("@age:[18 18]", t.translate(FilterExpression.eq("age", 18)));
		assertEquals("-(@name:{bob})", t.translate(FilterExpression.ne("name", "bob")));
		// In TAG
		String inTag = t.translate(FilterExpression.in("name", List.of("a", "b")));
		assertTrue(inTag.contains("@name:{a | b}"));
		// In NUMERIC
		String inNum = t.translate(FilterExpression.in("age", List.of(18, 20)));
		assertTrue(inNum.contains("(@age:[18 18])"));
		assertTrue(inNum.contains("|"));
		// 范围
		assertEquals("@score:[(1.5 +inf]", t.translate(FilterExpression.gt("score", 1.5)));
		assertEquals("@score:[1.5 +inf]", t.translate(FilterExpression.gte("score", 1.5)));
		assertEquals("@score:[-inf (1.5]", t.translate(FilterExpression.lt("score", 1.5)));
		assertEquals("@score:[-inf 1.5]", t.translate(FilterExpression.lte("score", 1.5)));
	}

	/** And / Or / Not 组合。 */
	@Test
	public void testRedisLogic() {
		RedisFilterTranslator t = new RedisFilterTranslator(java.util.Set.of("age"));
		String and = t.translate(FilterExpression.eq("a", "1").and(FilterExpression.eq("b", "2")));
		assertTrue(and.contains("(@a:{1}) (@b:{2})"));
		String or = t.translate(FilterExpression.eq("a", "1").or(FilterExpression.eq("b", "2")));
		assertTrue(or.contains("(@a:{1})|(@b:{2})"));
		String not = t.translate(FilterExpression.eq("a", "1").not());
		assertEquals("-(@a:{1})", not);
	}
}
