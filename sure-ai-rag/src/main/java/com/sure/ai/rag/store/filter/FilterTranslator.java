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

/**
 * 方言翻译器：把可移植 {@link FilterExpression} 翻译成目标向量库的原生过滤结构。
 *
 * <p>实现类通常返回 core {@link com.sure.ai.internal.json.JsonObject}，
 * 以便随后随 REST 请求体一起序列化。{@code null} 入参表示「不过滤」，
 * 实现应返回 {@code null}（或等价的空结构），由调用方决定是否附加到请求。</p>
 *
 * @param <T> 目标库原生过滤结构类型（通常为 JsonObject）
 * @author sureai
 * @since 1.8.0
 */
public interface FilterTranslator<T> {

	/**
	 * 翻译过滤表达式。
	 *
	 * @param filter 可移植表达式，可为 null
	 * @return 目标库原生过滤结构；入参为 null 时返回 null
	 */
	T translate(FilterExpression filter);
}
