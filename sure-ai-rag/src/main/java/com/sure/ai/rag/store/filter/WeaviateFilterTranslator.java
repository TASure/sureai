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

import java.util.List;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;

/**
 * Weaviate 方言翻译器：翻译成 GraphQL {@code where} 过滤对象。
 *
 * <p>结构为 {@code {path:[field], operator, valueX}}：
 * 逻辑节点 {@code And/Or} 用 {@code operands} 嵌套子条件，{@code Not} 用单子 {@code operands}；
 * 值字段按类型选择 {@code valueText/valueInt/valueNumber/valueBoolean}，
 * {@code In} 映射为 {@code ContainsAny} 加对应数组字段。</p>
 *
 * <p>语法来源：<a href="https://docs.weaviate.io/weaviate/search/filters">Weaviate Filters</a>。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class WeaviateFilterTranslator implements FilterTranslator<JsonObject> {

	/** 构造翻译器。 */
	public WeaviateFilterTranslator() {
	}

	@Override
	public JsonObject translate(FilterExpression filter) {
		if (filter == null) {
			return null;
		}
		return translateExpr(filter);
	}

	private JsonObject translateExpr(FilterExpression e) {
		if (e instanceof And and) {
			return logical("And", and.conditions());
		}
		if (e instanceof Or or) {
			return logical("Or", or.conditions());
		}
		if (e instanceof Not not) {
			JsonObject wrap = Json.object();
			wrap.set("operator", "Not");
			JsonArray operands = Json.array();
			operands.set(translateExpr(not.expression()));
			wrap.set("operands", operands);
			return wrap;
		}
		if (e instanceof Eq eq) {
			return leaf(eq.field(), "Equal", eq.value());
		}
		if (e instanceof Ne ne) {
			return leaf(ne.field(), "NotEqual", ne.value());
		}
		if (e instanceof In in) {
			return inLeaf(in.field(), in.values());
		}
		if (e instanceof Gt gt) {
			return leaf(gt.field(), "GreaterThan", gt.value());
		}
		if (e instanceof Gte gte) {
			return leaf(gte.field(), "GreaterThanEqual", gte.value());
		}
		if (e instanceof Lt lt) {
			return leaf(lt.field(), "LessThan", lt.value());
		}
		if (e instanceof Lte lte) {
			return leaf(lte.field(), "LessThanEqual", lte.value());
		}
		throw new IllegalArgumentException("不支持的 Weaviate 条件: " + e.getClass().getName());
	}

	private JsonObject logical(String operator, List<FilterExpression> children) {
		JsonObject wrap = Json.object();
		wrap.set("operator", operator);
		JsonArray operands = Json.array();
		for (FilterExpression c : children) {
			operands.set(translateExpr(c));
		}
		wrap.set("operands", operands);
		return wrap;
	}

	private JsonObject leaf(String field, String operator, Object value) {
		JsonObject node = Json.object();
		node.set("path", List.of(field));
		node.set("operator", operator);
		applyValue(node, valueFieldFor(value), value);
		return node;
	}

	private JsonObject inLeaf(String field, List<Object> values) {
		JsonObject node = Json.object();
		node.set("path", List.of(field));
		node.set("operator", "ContainsAny");
		Object first = values.get(0);
		applyValue(node, arrayFieldFor(first), values);
		return node;
	}

	private static String valueFieldFor(Object value) {
		if (value instanceof Number n) {
			return isIntegral(n) ? "valueInt" : "valueNumber";
		}
		if (value instanceof Boolean) {
			return "valueBoolean";
		}
		return "valueText";
	}

	private static String arrayFieldFor(Object value) {
		if (value instanceof Number n) {
			return isIntegral(n) ? "valueIntArray" : "valueNumberArray";
		}
		if (value instanceof Boolean) {
			return "valueBooleanArray";
		}
		return "valueTextArray";
	}

	private static void applyValue(JsonObject node, String field, Object value) {
		node.set(field, value);
	}

	private static boolean isIntegral(Number n) {
		double d = n.doubleValue();
		return d == Math.rint(d) && !Double.isInfinite(d);
	}
}
