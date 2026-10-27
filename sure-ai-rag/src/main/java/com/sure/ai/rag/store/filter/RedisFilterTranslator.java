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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RediSearch 方言翻译器：把可移植表达式翻译成 RediSearch 查询过滤子串，
 * 用于拼入 KNN 预过滤 {@code (filter)=>[KNN k @vec $blob]}。
 *
 * <p>RediSearch 查询语法：AND 为空格、OR 为 {@code |}、NOT 为 {@code -}；
 * TAG 字段用 {@code @field:{v1 | v2}} 精确匹配，NUMERIC 字段用 {@code @field:[min max]}
 * 闭区间，开区间用 {@code (} 前缀（如 {@code @field:[(1 +inf]}）。</p>
 *
 * <p>字段类型由构造时传入的 {@code numericFields} 集合决定：集合内字段按 NUMERIC 渲染，
 * 其余按 TAG 渲染。这与 {@code FT.CREATE} 的 SCHEMA 声明对应。</p>
 *
 * <p>语法来源：
 * <a href="https://redis.io/docs/latest/develop/interact/search-and-query/query/vector-search/">Vector search</a>、
 * <a href="https://redis.io/docs/latest/develop/ai/search-and-query/indexing/field-and-type-options/">Field and type options</a>。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class RedisFilterTranslator implements FilterTranslator<String> {

	/** 数值型字段名集合（其余元数据字段按 TAG 处理）。 */
	private final Set<String> numericFields;

	/**
	 * 构造翻译器。
	 *
	 * @param numericFields 声明为 NUMERIC 的字段名集合，可为空（全部按 TAG）
	 */
	public RedisFilterTranslator(Set<String> numericFields) {
		this.numericFields = numericFields == null ? new LinkedHashSet<>() : new LinkedHashSet<>(numericFields);
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
			StringBuilder sb = new StringBuilder();
			for (FilterExpression c : and.conditions()) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append('(').append(expr(c)).append(')');
			}
			return sb.toString();
		}
		if (e instanceof Or or) {
			StringBuilder sb = new StringBuilder();
			for (FilterExpression c : or.conditions()) {
				if (sb.length() > 0) {
					sb.append('|');
				}
				sb.append('(').append(expr(c)).append(')');
			}
			return sb.toString();
		}
		if (e instanceof Not not) {
			return "-(" + expr(not.expression()) + ")";
		}
		if (e instanceof Eq eq) {
			return eq(eq.field(), eq.value());
		}
		if (e instanceof Ne ne) {
			return "-(" + eq(ne.field(), ne.value()) + ")";
		}
		if (e instanceof In in) {
			return in(in.field(), in.values());
		}
		if (e instanceof Gt gt) {
			return range(gt.field(), "(" + num(gt.value()), "+inf");
		}
		if (e instanceof Gte gte) {
			return range(gte.field(), num(gte.value()), "+inf");
		}
		if (e instanceof Lt lt) {
			return range(lt.field(), "-inf", "(" + num(lt.value()));
		}
		if (e instanceof Lte lte) {
			return range(lte.field(), "-inf", num(lte.value()));
		}
		throw new IllegalArgumentException("不支持的 RediSearch 条件: " + e.getClass().getName());
	}

	private String eq(String field, Object value) {
		if (numericFields.contains(field)) {
			String v = num(value);
			return "@" + field + ":[" + v + " " + v + "]";
		}
		return "@" + field + ":{" + tag(value) + "}";
	}

	private String in(String field, List<Object> values) {
		if (numericFields.contains(field)) {
			StringBuilder sb = new StringBuilder();
			for (Object v : values) {
				if (sb.length() > 0) {
					sb.append('|');
				}
				String n = num(v);
				sb.append("(@").append(field).append(":[").append(n).append(' ').append(n).append("])");
			}
			return sb.toString();
		}
		StringBuilder sb = new StringBuilder();
		sb.append('@').append(field).append(":{");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(" | ");
			}
			sb.append(tag(values.get(i)));
		}
		sb.append('}');
		return sb.toString();
	}

	private static String range(String field, String lower, String upper) {
		return "@" + field + ":[" + lower + " " + upper + "]";
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

	private static String tag(Object value) {
		String s = String.valueOf(value);
		StringBuilder sb = new StringBuilder(s.length() + 4);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ("\\{}|, ".indexOf(c) >= 0) {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.toString();
	}
}
