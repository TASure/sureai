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
 * Pinecone 方言翻译器：翻译成 Pinecone metadata filter（{@code $eq/$ne/$in/$gt/...} 风格）。
 *
 * <p>Pinecone 无通用 NOT 运算符，{@link Not} 通过德摩根律与叶子取反消除：
 * {@code Not(Eq)=Ne}、{@code Not(In)=$nin}、{@code Not(Gt)=Lte}、
 * {@code Not(And)=Or(...)}} 等。逻辑节点形如 {@code {"$and":[...]}} / {@code {"$or":[...]}}。</p>
 *
 * <p>语法来源：<a href="https://docs.pinecone.io/reference/api/2025-01/data-plane/query">Query vectors</a>、
 * <a href="https://docs.pinecone.io/guides/search/filtering">Metadata filtering</a>。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class PineconeFilterTranslator implements FilterTranslator<JsonObject> {

	/** 构造翻译器。 */
	public PineconeFilterTranslator() {
	}

	@Override
	public JsonObject translate(FilterExpression filter) {
		if (filter == null) {
			return null;
		}
		return translateExpr(filter);
	}

	private JsonObject translateExpr(FilterExpression e) {
		if (e instanceof Not not) {
			return translateNot(not.expression());
		}
		if (e instanceof And and) {
			return logical("$and", and.conditions());
		}
		if (e instanceof Or or) {
			return logical("$or", or.conditions());
		}
		if (e instanceof Eq eq) {
			return single(eq.field(), "$eq", eq.value());
		}
		if (e instanceof Ne ne) {
			return single(ne.field(), "$ne", ne.value());
		}
		if (e instanceof In in) {
			return single(in.field(), "$in", in.values());
		}
		if (e instanceof Gt gt) {
			return single(gt.field(), "$gt", gt.value());
		}
		if (e instanceof Gte gte) {
			return single(gte.field(), "$gte", gte.value());
		}
		if (e instanceof Lt lt) {
			return single(lt.field(), "$lt", lt.value());
		}
		if (e instanceof Lte lte) {
			return single(lte.field(), "$lte", lte.value());
		}
		throw new IllegalArgumentException("不支持的 Pinecone 条件: " + e.getClass().getName());
	}

	private JsonObject translateNot(FilterExpression child) {
		if (child instanceof Eq eq) {
			return single(eq.field(), "$ne", eq.value());
		}
		if (child instanceof Ne ne) {
			return single(ne.field(), "$eq", ne.value());
		}
		if (child instanceof In in) {
			return single(in.field(), "$nin", in.values());
		}
		if (child instanceof Gt gt) {
			return single(gt.field(), "$lte", gt.value());
		}
		if (child instanceof Gte gte) {
			return single(gte.field(), "$lt", gte.value());
		}
		if (child instanceof Lt lt) {
			return single(lt.field(), "$gte", lt.value());
		}
		if (child instanceof Lte lte) {
			return single(lte.field(), "$gt", lte.value());
		}
		if (child instanceof And and) {
			JsonArray arr = Json.array();
			for (FilterExpression c : and.conditions()) {
				arr.set(translateNot(c));
			}
			JsonObject wrap = Json.object();
			wrap.set("$or", arr);
			return wrap;
		}
		if (child instanceof Or or) {
			JsonArray arr = Json.array();
			for (FilterExpression c : or.conditions()) {
				arr.set(translateNot(c));
			}
			JsonObject wrap = Json.object();
			wrap.set("$and", arr);
			return wrap;
		}
		if (child instanceof Not inner) {
			return translateExpr(inner.expression());
		}
		throw new IllegalArgumentException("不支持的 Pinecone 取反: " + child.getClass().getName());
	}

	private JsonObject logical(String op, java.util.List<FilterExpression> children) {
		JsonArray arr = Json.array();
		for (FilterExpression c : children) {
			arr.set(translateExpr(c));
		}
		JsonObject wrap = Json.object();
		wrap.set(op, arr);
		return wrap;
	}

	private static JsonObject single(String field, String op, Object value) {
		JsonObject inner = Json.object();
		inner.set(op, value);
		JsonObject outer = Json.object();
		outer.set(field, inner);
		return outer;
	}
}
