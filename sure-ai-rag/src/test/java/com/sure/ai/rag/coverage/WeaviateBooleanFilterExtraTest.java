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
package com.sure.ai.rag.coverage;

import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.WeaviateFilterTranslator;

/**
 * WeaviateFilterTranslator：In boolean 值条件 → valueBooleanArray 分支补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class WeaviateBooleanFilterExtraTest {

	/** Weaviate：in boolean → valueBooleanArray 字段。 */
	@Test
	public void testWeaviateBooleanIn() {
		WeaviateFilterTranslator t = new WeaviateFilterTranslator();
		FilterExpression in = FilterExpression.in("flag", List.of(true));
		assertNotNull(t.translate(in));
	}
}
