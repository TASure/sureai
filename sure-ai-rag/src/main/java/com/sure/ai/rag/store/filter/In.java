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

import com.sure.tool.lang.Assert;

/**
 * 包含条件：{@code field} 的值属于 {@code values} 之一。
 *
 * @param field  字段名
 * @param values 候选值列表
 * @author sureai
 * @since 1.8.0
 */
public record In(String field, List<Object> values) implements FilterExpression {

	/**
	 * 紧凑构造器：校验字段与候选值，并复制为不可变列表。
	 *
	 * @param field  字段名
	 * @param values 候选值列表
	 */
	public In {
		Assert.notBlank(field, "filter field 不能为空");
		Assert.notEmpty(values, "In.values 不能为空");
		values = List.copyOf(values);
	}
}
