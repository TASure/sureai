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

import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.store.ElasticsearchVectorStore;
import com.sure.ai.rag.store.OpenSearchVectorStore;

/**
 * OpenSearch/Elasticsearch clear() 死端口异常分支补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class EsOsClearExtraTest {

	/** OpenSearch：clear() 死端口 → IOException 捕获分支。 */
	@Test
	public void testOpenSearchClearDeadPort() {
		OpenSearchVectorStore store = OpenSearchVectorStore.builder()
				.baseUrl("http://127.0.0.1:1").indexName("idx").build();
		assertThrows(AiException.class, store::clear);
	}

	/** Elasticsearch：clear() 死端口 → IOException 捕获分支。 */
	@Test
	public void testEsClearDeadPort() {
		ElasticsearchVectorStore store = ElasticsearchVectorStore.builder()
				.baseUrl("http://127.0.0.1:1").indexName("idx").build();
		assertThrows(AiException.class, store::clear);
	}
}
