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
import com.sure.ai.rag.store.ChromaVectorStore;
import com.sure.ai.rag.store.PineconeVectorStore;

/**
 * HTTP store 死端口异常分支 + builder setter 补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class StoreDeadPortExtraTest {

	private static final float[] EMB = new float[] { 0.1f, 0.2f, 0.3f };

	/** Pinecone：3参重载委托。 */
	@Test
	public void testPineconeThreeArg() {
		PineconeVectorStore store = PineconeVectorStore.builder()
				.baseUrl("http://127.0.0.1:1").apiKey("k").build();
		assertThrows(AiException.class, () -> store.similaritySearch(EMB, 5, 0.5));
	}

	/** Chroma：httpClient setter + 死端口。 */
	@Test
	public void testChromaHttpClientSetter() {
		assertThrows(AiException.class, () -> ChromaVectorStore.builder()
				.baseUrl("http://127.0.0.1:1")
				.collectionName("c")
				.httpClient(java.net.http.HttpClient.newHttpClient())
				.build());
	}
}
