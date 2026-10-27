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

import com.sure.tool.lang.Assert;

/**
 * 逻辑非：对单子条件取反。
 *
 * @param expression 被取反的子表达式
 * @author sureai
 * @since 1.8.0
 */
public record Not(FilterExpression expression) implements FilterExpression {

	/**
	 * 紧凑构造器：校验子表达式非空。
	 *
	 * @param expression 被取反的子表达式
	 */
	public Not {
		Assert.notNull(expression, "Not.expression 不能为 null");
	}
}
