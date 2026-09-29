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
import com.sure.ai.internal.json.JsonObject;

/**
 * Elasticsearch 方言翻译器：把可移植表达式翻译成 Elasticsearch query-dsl 过滤对象，
 * 用于嵌入 kNN 查询的 {@code knn.filter} 字段。
 *
 * <p>Elasticsearch 布尔查询为三桶：{@code bool.must}（AND）、{@code bool.should}（OR）、
 * {@code bool.must_not}（NOT）。叶子条件：{@code term}（等值）、{@code terms}（集合）、
 * {@code range}（gt/gte/lt/lte）。{@link Ne} 退化为 {@code must_not term}。</p>
 *
 * <p>语法来源：
 * <a href="https://www.elastic.co/guide/en/elasticsearch/reference/8.3/knn-search-api.html">kNN search API</a>、
 * <a href="https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-knn-query">Knn query</a>。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class ElasticsearchFilterTranslator implements FilterTranslator<JsonObject> {

	/** 构造翻译器。 */
	public ElasticsearchFilterTranslator() {
	}

	@Override
	public JsonObject translate(FilterExpression filter) {
		if (filter == null) {
			return null;
		}
		return translateExpr(filter);
	}

	/**
	 * 递归翻译单个表达式为 query-dsl 对象。
	 *
	 * @param e 表达式
	 * @return query-dsl 对象
	 */
	private JsonObject translateExpr(FilterExpression e) {
		if (e instanceof And and) {
			return bool("must", and.conditions());
		}
		if (e instanceof Or or) {
			return bool("should", or.conditions());
		}
		if (e instanceof Not not) {
			JsonArray mustNot = Json.array();
			mustNot.set(translateExpr(not.expression()));
			JsonObject bool = Json.object();
			bool.set("must_not", mustNot);
			JsonObject wrap = Json.object();
			wrap.set("bool", bool);
			return wrap;
		}
		if (e instanceof Eq eq) {
			return term(eq.field(), eq.value());
		}
		if (e instanceof Ne ne) {
			JsonArray mustNot = Json.array();
			mustNot.set(term(ne.field(), ne.value()));
			JsonObject bool = Json.object();
			bool.set("must_not", mustNot);
			JsonObject wrap = Json.object();
			wrap.set("bool", bool);
			return wrap;
		}
		if (e instanceof In in) {
			JsonObject f = Json.object();
			f.set(in.field(), in.values());
			JsonObject wrap = Json.object();
			wrap.set("terms", f);
			return wrap;
		}
		if (e instanceof Gt gt) {
			return range(gt.field(), "gt", gt.value());
		}
		if (e instanceof Gte gte) {
			return range(gte.field(), "gte", gte.value());
		}
		if (e instanceof Lt lt) {
			return range(lt.field(), "lt", lt.value());
		}
		if (e instanceof Lte lte) {
			return range(lte.field(), "lte", lte.value());
		}
		throw new IllegalArgumentException("不支持的 Elasticsearch 条件: " + e.getClass().getName());
	}

	private JsonObject bool(String bucket, java.util.List<FilterExpression> children) {
		JsonArray arr = Json.array();
		for (FilterExpression c : children) {
			arr.set(translateExpr(c));
		}
		JsonObject bool = Json.object();
		bool.set(bucket, arr);
		JsonObject wrap = Json.object();
		wrap.set("bool", bool);
		return wrap;
	}

	private static JsonObject term(String field, Object value) {
		JsonObject f = Json.object();
		f.set(field, value);
		JsonObject wrap = Json.object();
		wrap.set("term", f);
		return wrap;
	}

	private static JsonObject range(String field, String op, Object value) {
		JsonObject r = Json.object();
		r.set(op, value);
		JsonObject f = Json.object();
		f.set(field, r);
		JsonObject wrap = Json.object();
		wrap.set("range", f);
		return wrap;
	}
}
