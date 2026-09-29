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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可移植的元数据过滤表达式：以 SQL-like 抽象描述「字段条件 + 逻辑组合」，
 * 再由各向量库方言翻译器（{@link FilterTranslator}）转成库原生过滤结构。
 *
 * <p>层次结构为密封接口：</p>
 * <ul>
 *   <li>逻辑组合：{@link And}、{@link Or}、{@link Not}；</li>
 *   <li>字段条件：{@link Eq}、{@link Ne}、{@link In}、{@link Gt}、{@link Gte}、{@link Lt}、{@link Lte}。</li>
 * </ul>
 *
 * <p>值类型约定：字符串/布尔直接按原样翻译；数值（{@link Number}）用于范围比较。
 * 由于 {@code Vector.metadata} 为 {@code Map<String,String>}，跨库数值字段通常以字符串落库，
 * 调用方在构造范围条件时传入 {@link Number} 即可，翻译器负责按方言映射为对应类型。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * FilterExpression f = FilterExpression.eq("tenant", "acme")
 *         .and(FilterExpression.in("status", List.of("ok", "pending"))
 *                 .or(FilterExpression.gt("score", 0.8)));
 * }</pre>
 *
 * @author sureai
 * @since 1.8.0
 */
public sealed interface FilterExpression
		permits And, Or, Not, Eq, Ne, In, Gt, Gte, Lt, Lte {

	/**
	 * 构造等值条件。
	 *
	 * @param field 字段名
	 * @param value 期望值（String/Number/Boolean）
	 * @return 等值条件
	 */
	static Eq eq(String field, Object value) {
		return new Eq(field, value);
	}

	/**
	 * 构造不等条件。
	 *
	 * @param field 字段名
	 * @param value 排除值
	 * @return 不等条件
	 */
	static Ne ne(String field, Object value) {
		return new Ne(field, value);
	}

	/**
	 * 构造「字段值在集合内」条件。
	 *
	 * @param field  字段名
	 * @param values 候选值列表
	 * @return in 条件
	 */
	static In in(String field, List<Object> values) {
		return new In(field, values);
	}

	/**
	 * 构造「大于」条件。
	 *
	 * @param field 字段名
	 * @param value 下界（不含）
	 * @return 大于条件
	 */
	static Gt gt(String field, Object value) {
		return new Gt(field, value);
	}

	/**
	 * 构造「大于等于」条件。
	 *
	 * @param field 字段名
	 * @param value 下界（含）
	 * @return 大于等于条件
	 */
	static Gte gte(String field, Object value) {
		return new Gte(field, value);
	}

	/**
	 * 构造「小于」条件。
	 *
	 * @param field 字段名
	 * @param value 上界（不含）
	 * @return 小于条件
	 */
	static Lt lt(String field, Object value) {
		return new Lt(field, value);
	}

	/**
	 * 构造「小于等于」条件。
	 *
	 * @param field 字段名
	 * @param value 上界（含）
	 * @return 小于等于条件
	 */
	static Lte lte(String field, Object value) {
		return new Lte(field, value);
	}

	/**
	 * 逻辑与：组合本表达式与其余表达式。
	 *
	 * @param others 其余表达式
	 * @return and 组合
	 */
	default And and(FilterExpression... others) {
		List<FilterExpression> list = new ArrayList<>();
		list.add(this);
		Collections.addAll(list, others);
		return new And(List.copyOf(list));
	}

	/**
	 * 逻辑或：组合本表达式与其余表达式。
	 *
	 * @param others 其余表达式
	 * @return or 组合
	 */
	default Or or(FilterExpression... others) {
		List<FilterExpression> list = new ArrayList<>();
		list.add(this);
		Collections.addAll(list, others);
		return new Or(List.copyOf(list));
	}

	/**
	 * 逻辑非：对本表达式取反。
	 *
	 * @return not 组合
	 */
	default Not not() {
		return new Not(this);
	}
}
