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

/**
 * Typesense 方言翻译器：把可移植表达式翻译成 Typesense {@code filter_by} 字符串。
 *
 * <p>语法（<a href="https://typesense.org/docs/30.1/api/search.html#filter-parameters">Filter parameters</a>）：
 * AND 为 {@code &&}、OR 为 {@code ||}；等值 {@code field:=v}、不等 {@code field:!=v}；
 * 范围 {@code field:>n / >=n / <n / <=n}；集合 {@code field:[a,b,c]}；
 * 逻辑非 {@code NOT (...)}。字符串值用双引号包裹，内部双引号翻倍转义。</p>
 *
 * <p><b>能力边界（如实标注）</b>：覆盖密封表达式的叶子与逻辑组合有限子集；
 * 不支持存在性、通配、地理等高级过滤语义。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class TypesenseFilterTranslator implements FilterTranslator<String> {

	/** 构造翻译器。 */
	public TypesenseFilterTranslator() {
	}

	@Override
	public String translate(FilterExpression filter) {
		if (filter == null) {
			return null;
		}
		return expr(filter);
	}

	private String expr(FilterExpression e) {
		if (e instanceof And and) {
			return join(and.conditions(), " && ");
		}
		if (e instanceof Or or) {
			return join(or.conditions(), " || ");
		}
		if (e instanceof Not not) {
			return "NOT (" + expr(not.expression()) + ")";
		}
		if (e instanceof Eq eq) {
			return eq.field() + ":=" + literal(eq.value());
		}
		if (e instanceof Ne ne) {
			return ne.field() + ":!=" + literal(ne.value());
		}
		if (e instanceof In in) {
			StringBuilder sb = new StringBuilder(in.field()).append(":[");
			List<Object> values = in.values();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(literal(values.get(i)));
			}
			return sb.append(']').toString();
		}
		if (e instanceof Gt gt) {
			return gt.field() + ":>" + num(gt.value());
		}
		if (e instanceof Gte gte) {
			return gte.field() + ":>=" + num(gte.value());
		}
		if (e instanceof Lt lt) {
			return lt.field() + ":<" + num(lt.value());
		}
		if (e instanceof Lte lte) {
			return lte.field() + ":<=" + num(lte.value());
		}
		throw new IllegalArgumentException("不支持的 Typesense 条件: " + e.getClass().getName());
	}

	private String join(List<FilterExpression> conditions, String sep) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < conditions.size(); i++) {
			if (i > 0) {
				sb.append(sep);
			}
			sb.append('(').append(expr(conditions.get(i))).append(')');
		}
		return sb.toString();
	}

	private static String literal(Object value) {
		if (value instanceof Number n) {
			return num(n);
		}
		if (value instanceof Boolean b) {
			return String.valueOf(b);
		}
		return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
	}

	private static String num(Object value) {
		if (value instanceof Number n) {
			double d = n.doubleValue();
			if (d == Math.rint(d) && !Double.isInfinite(d)) {
				return String.valueOf((long) d);
			}
			return String.valueOf(d);
		}
		return String.valueOf(value);
	}
}
