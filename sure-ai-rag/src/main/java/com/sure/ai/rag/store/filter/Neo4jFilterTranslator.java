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
 * Neo4j Cypher 方言翻译器：把可移植表达式翻译成 Cypher {@code WHERE} 片段（不含 {@code WHERE} 关键字）。
 *
 * <p>假设节点以 {@code n} 别名访问，元数据即节点属性：</p>
 * <ul>
 *   <li>Eq：{@code n.tenant = 'v'}；Ne：{@code n.tenant &lt;&gt; 'v'}；</li>
 *   <li>In：{@code n.status IN ['a','b']}；范围：{@code n.score &gt; 1.5} 等；</li>
 *   <li>逻辑：AND/OR 子句加括号、Not 用 {@code NOT (expr)}。</li>
 * </ul>
 *
 * <p><b>能力边界（如实标注）</b>：覆盖密封表达式全部叶子与逻辑组合；字符串字面量经单引号翻倍转义。
 * 该谓词追加在 {@code CALL db.index.vector.queryNodes(...) YIELD node, score} 之后作为 {@code WHERE}，
 * 属后过滤——可能使命中数少于请求的 topK（Neo4j 2026 的 SEARCH 前置过滤为更优形态，本实现未覆盖）。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class Neo4jFilterTranslator implements FilterTranslator<String> {

	/** 构造翻译器。 */
	public Neo4jFilterTranslator() {
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
					sb.append(" AND ");
				}
				sb.append('(').append(expr(c)).append(')');
			}
			return sb.toString();
		}
		if (e instanceof Or or) {
			StringBuilder sb = new StringBuilder();
			for (FilterExpression c : or.conditions()) {
				if (sb.length() > 0) {
					sb.append(" OR ");
				}
				sb.append('(').append(expr(c)).append(')');
			}
			return sb.toString();
		}
		if (e instanceof Not not) {
			return "NOT (" + expr(not.expression()) + ")";
		}
		if (e instanceof Eq eq) {
			return prop(eq.field()) + " = " + literal(eq.value());
		}
		if (e instanceof Ne ne) {
			return prop(ne.field()) + " <> " + literal(ne.value());
		}
		if (e instanceof In in) {
			StringBuilder sb = new StringBuilder(prop(in.field())).append(" IN [");
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
			return prop(gt.field()) + " > " + num(gt.value());
		}
		if (e instanceof Gte gte) {
			return prop(gte.field()) + " >= " + num(gte.value());
		}
		if (e instanceof Lt lt) {
			return prop(lt.field()) + " < " + num(lt.value());
		}
		if (e instanceof Lte lte) {
			return prop(lte.field()) + " <= " + num(lte.value());
		}
		throw new IllegalArgumentException("不支持的 Neo4j 条件: " + e.getClass().getName());
	}

	private static String prop(String field) {
		return "n." + field;
	}

	private static String literal(Object value) {
		if (value instanceof Number n) {
			return num(n);
		}
		if (value instanceof Boolean b) {
			return b ? "true" : "false";
		}
		return "'" + escape(String.valueOf(value)) + "'";
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

	private static String escape(String s) {
		return s.replace("'", "''");
	}
}
