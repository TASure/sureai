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
 * Cassandra（CQL）方言翻译器：把可移植表达式翻译成 CQL {@code WHERE} 片段（不含 {@code WHERE} 关键字）。
 *
 * <p>本实现假设元数据以 {@code map<text,text>} 列 {@code metadata} 落库
 * （见 {@code CassandraVectorStore} 建表语句），因此字段条件一律翻译成
 * {@code metadata['field']} 取值表达式：</p>
 * <ul>
 *   <li>等值：{@code metadata['field'] = 'v'}；</li>
 *   <li>In：{@code metadata['field'] IN ('a','b')}；</li>
 *   <li>逻辑组合：AND 用 {@code AND}、OR 用 {@code OR}，子句加括号。</li>
 * </ul>
 *
 * <p><b>能力边界（如实标注）</b>：CQL 的 {@code WHERE} 语义远窄于 SQL——
 * 它主要服务于主键/索引约束，且在 ANN（{@code ORDER BY embedding ANN OF ?}）检索中，
 * 仅当被过滤列建有 SAI（Storage-Attached Indexing）索引时才允许 {@code metadata['k']=v}
 * 谓词。本翻译器<b>仅覆盖 Eq / In / And / Or</b> 这一可在 SAI 上成立的有限子集；
 * <b>不等（Ne）、逻辑非（Not）与范围比较（Gt/Gte/Lt/Lte）在 CQL WHERE 中无等价表达，
 * 一律抛出 {@link IllegalArgumentException}</b>，由调用方感知不支持而非静默忽略。
 * 字符串字面量通过单引号翻倍转义（{@code '} -> {@code ''}）。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class CassandraFilterTranslator implements FilterTranslator<String> {

	/** 元数据 map 列取字段值的表达式前缀。 */
	private static final String META = "metadata['";

	/** 构造翻译器。 */
	public CassandraFilterTranslator() {
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
		if (e instanceof Eq eq) {
			return col(eq.field()) + " = " + literal(eq.value());
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
		if (e instanceof Not || e instanceof Ne || e instanceof Gt || e instanceof Gte
				|| e instanceof Lt || e instanceof Lte) {
			throw new IllegalArgumentException("CQL WHERE 不支持该过滤条件（仅支持 Eq/In/And/Or）: "
					+ e.getClass().getSimpleName());
		}
		throw new IllegalArgumentException("不支持的 Cassandra 条件: " + e.getClass().getName());
	}

	private static String col(String field) {
		return META + field + "']";
	}

	private static String literal(Object value) {
		if (value instanceof Number n) {
			return String.valueOf(n.doubleValue());
		}
		if (value instanceof Boolean b) {
			return b ? "true" : "false";
		}
		return "'" + escape(String.valueOf(value)) + "'";
	}

	private static String escape(String s) {
		return s.replace("'", "''");
	}
}
