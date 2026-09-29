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

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * Qdrant 方言翻译器：把可移植表达式翻译成 Qdrant REST {@code filter} 结构。
 *
 * <p>Qdrant filter 为三桶模型：{@code must}（AND）、{@code must_not}（NOT）、{@code should}（OR），
 * 三者之间隐式 AND。字段条件形如 {@code {key, match:{value}}}、
 * {@code {key, match:{any:[...]}}}、{@code {key, range:{gt/gte/lt/lte}}}。
 * 嵌套组合通过 {@code {filter: {...}}} 字段条件表达。</p>
 *
 * <p>语法来源：<a href="https://qdrant.tech/documentation/search/filtering/">Qdrant Filtering</a>、
 * <a href="https://api.qdrant.tech/v-1-12-x/api-reference/search/points">Search points API</a>。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class QdrantFilterTranslator implements FilterTranslator<JsonObject> {

	/** 构造翻译器。 */
	public QdrantFilterTranslator() {
	}

	@Override
	public JsonObject translate(FilterExpression filter) {
		if (filter == null) {
			return null;
		}
		return toFilter(filter);
	}

	/**
	 * 把任意表达式翻译成 Qdrant filter 对象。
	 *
	 * @param e 表达式
	 * @return Qdrant filter
	 */
	private JsonObject toFilter(FilterExpression e) {
		if (e instanceof Or or) {
			JsonObject f = Json.object();
			JsonArray should = Json.array();
			for (FilterExpression c : or.conditions()) {
				should.set(toNestedCondition(c));
			}
			f.set("should", should);
			return f;
		}
		JsonArray must = Json.array();
		JsonArray mustNot = Json.array();
		collect(e, must, mustNot);
		JsonObject f = Json.object();
		if (must.size() > 0) {
			f.set("must", must);
		}
		if (mustNot.size() > 0) {
			f.set("must_not", mustNot);
		}
		return f;
	}

	/**
	 * 在 AND 上下文里收集正/负叶子条件；OR 子组包装为嵌套 filter 放入 must。
	 *
	 * @param e        表达式
	 * @param must     must 桶（出参）
	 * @param mustNot  must_not 桶（出参）
	 */
	private void collect(FilterExpression e, JsonArray must, JsonArray mustNot) {
		if (e instanceof And and) {
			for (FilterExpression c : and.conditions()) {
				collect(c, must, mustNot);
			}
		} else if (e instanceof Or or) {
			JsonArray should = Json.array();
			for (FilterExpression c : or.conditions()) {
				should.set(toNestedCondition(c));
			}
			JsonObject nestedFilter = Json.object();
			nestedFilter.set("should", should);
			JsonObject wrap = Json.object();
			wrap.set("filter", nestedFilter);
			must.set(wrap);
		} else if (e instanceof Not not) {
			JsonObject childFilter = toFilter(not.expression());
			if (childFilter.size() == 1 && childFilter.has("must")) {
				JsonArray arr = childFilter.getJsonArray("must");
				for (int i = 0; i < arr.size(); i++) {
					mustNot.set(arr.get(i));
				}
			} else {
				JsonObject wrap = Json.object();
				wrap.set("filter", childFilter);
				mustNot.set(wrap);
			}
		} else if (e instanceof Ne ne) {
			mustNot.set(eqCondition(ne.field(), ne.value()));
		} else {
			must.set(fieldCondition(e));
		}
	}

	/**
	 * 生成 should 数组或嵌套上下文中的单子项。
	 *
	 * @param e 子表达式
	 * @return Qdrant 字段条件或嵌套 filter 包装
	 */
	private JsonElement toNestedCondition(FilterExpression e) {
		if (e instanceof And || e instanceof Or || e instanceof Not) {
			JsonObject wrap = Json.object();
			wrap.set("filter", toFilter(e));
			return wrap;
		}
		if (e instanceof Ne ne) {
			JsonObject inner = Json.object();
			JsonArray mustNot = Json.array();
			mustNot.set(eqCondition(ne.field(), ne.value()));
			inner.set("must_not", mustNot);
			JsonObject wrap = Json.object();
			wrap.set("filter", inner);
			return wrap;
		}
		return fieldCondition(e);
	}

	/**
	 * 字段叶子条件 → Qdrant FieldCondition。
	 *
	 * @param e 叶子表达式
	 * @return 字段条件对象
	 */
	private JsonObject fieldCondition(FilterExpression e) {
		if (e instanceof Eq eq) {
			return eqCondition(eq.field(), eq.value());
		}
		if (e instanceof In in) {
			JsonObject c = Json.object();
			c.set("key", in.field());
			JsonObject m = Json.object();
			m.set("any", in.values());
			c.set("match", m);
			return c;
		}
		if (e instanceof Gt gt) {
			return rangeCondition(gt.field(), "gt", gt.value());
		}
		if (e instanceof Gte gte) {
			return rangeCondition(gte.field(), "gte", gte.value());
		}
		if (e instanceof Lt lt) {
			return rangeCondition(lt.field(), "lt", lt.value());
		}
		if (e instanceof Lte lte) {
			return rangeCondition(lte.field(), "lte", lte.value());
		}
		throw new IllegalArgumentException("不支持的 Qdrant 条件: " + e.getClass().getName());
	}

	private static JsonObject eqCondition(String field, Object value) {
		JsonObject c = Json.object();
		c.set("key", field);
		JsonObject m = Json.object();
		m.set("value", value);
		c.set("match", m);
		return c;
	}

	private static JsonObject rangeCondition(String field, String op, Object value) {
		JsonObject c = Json.object();
		c.set("key", field);
		JsonObject r = Json.object();
		r.set(op, value);
		c.set("range", r);
		return c;
	}
}
