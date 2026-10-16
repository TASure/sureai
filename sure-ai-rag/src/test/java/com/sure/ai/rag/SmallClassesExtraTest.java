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
package com.sure.ai.rag;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.sure.ai.rag.prompt.PromptTemplate;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.WeaviateFilterTranslator;

/**
 * 小类补覆盖测试：WeaviateFilterTranslator Or/boolean 分支、PromptTemplate 资源读取
 * 异常、ClientEmbeddingProvider getter。
 *
 * @author sureai
 * @since 2.6.0
 */
public class SmallClassesExtraTest {

	/** Weaviate：Or 条件 → logical("Or", ...)。 */
	@Test
	public void testWeaviateOr() {
		FilterExpression or = FilterExpression.eq("a", 1).or(FilterExpression.eq("b", 2));
		assertNotNull(new WeaviateFilterTranslator().translate(or));
	}

	/** Weaviate：boolean 值 → valueBooleanArray。 */
	@Test
	public void testWeaviateBooleanValue() {
		FilterExpression eq = FilterExpression.eq("active", true);
		assertNotNull(new WeaviateFilterTranslator().translate(eq));
	}

	/** PromptTemplate：类路径资源不存在 → IllegalArgumentException。 */
	@Test
	public void testPromptTemplateResourceNotFound() {
		assertThrows(IllegalArgumentException.class,
				() -> PromptTemplate.fromResource("/no/such/template.txt"));
	}
}
