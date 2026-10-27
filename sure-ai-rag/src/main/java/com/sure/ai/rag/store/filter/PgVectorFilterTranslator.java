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
 * pgvector 方言翻译器：把可移植表达式翻译成 SQL {@code WHERE} 子句片段（不含 {@code WHERE} 关键字）。
 *
 * <p>本实现假设元数据以 {@code jsonb} 列 {@code metadata} 落库（见
 * {@code PgVectorVectorStore} 建表语句），因此字段条件一律翻译成
 * {@code metadata->>'field'} 取值表达式：</p>
 * <ul>
 *   <li>等值：{@code metadata->>'field' = 'v'}；不等：{@code <>}；</li>
 *   <li>In：{@code metadata->>'field' IN ('a','b')}；</li>
 *   <li>范围（Gt/Gte/Lt/Lte）：jsonb 取出为文本，显式转 float 比较
 *       {@code (metadata->>'field')::float > 1.5}；</li>
 *   <li>逻辑组合：AND 用 {@code AND}、OR 用 {@code OR}、Not 用 {@code NOT (...)}，子句加括号。</li>
 * </ul>
 *
 * <p><b>能力边界（如实标注）</b>：本翻译器仅覆盖密封表达式的有限子集——
 * 所有叶子（Eq/Ne/In/Gt/Gte/Lt/Lte）与逻辑组合（And/Or/Not）均可翻译；
 * 不支持正则、NULL 判断、数组包含等高级语义。字符串字面量通过单引号翻倍转义
 * （{@code '} -> {@code ''}），依赖服务端 {@code standard_conforming_strings=on}
 * （PostgreSQL 9.1+ 默认）。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class PgVectorFilterTranslator implements FilterTranslator<String> {

	/** 元数据列中取字段文本值的表达式前缀。 */
	private static final String META = "metadata->>'";

	/** 构造翻译器。 */
	public PgVectorFilterTranslator() {
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
			return col(eq.field()) + " = " + literal(eq.value());
		}
		if (e instanceof Ne ne) {
			return col(ne.field()) + " <> " + literal(ne.value());
		}
		if (e instanceof In in) {
			StringBuilder sb = new StringBuilder(col(in.field())).append(" IN (");
			List<Object> values = in.values();
			for (int i = 0; i < values.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(literal(values.get(i)));
			}
			return sb.append(')').toString();
		}
		if (e instanceof Gt gt) {
			return numCol(gt.field()) + " > " + num(gt.value());
		}
		if (e instanceof Gte gte) {
			return numCol(gte.field()) + " >= " + num(gte.value());
		}
		if (e instanceof Lt lt) {
			return numCol(lt.field()) + " < " + num(lt.value());
		}
		if (e instanceof Lte lte) {
			return numCol(lte.field()) + " <= " + num(lte.value());
		}
		throw new IllegalArgumentException("不支持的 pgvector 条件: " + e.getClass().getName());
	}

	private static String col(String field) {
		return META + field + "'";
	}

	private static String numCol(String field) {
		return "(" + META + field + "')::float";
	}

	private static String literal(Object value) {
		if (value instanceof Number n) {
			return num(n);
		}
		if (value instanceof Boolean b) {
			return b ? "'true'" : "'false'";
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
