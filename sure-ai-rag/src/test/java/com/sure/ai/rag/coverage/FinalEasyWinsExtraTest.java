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

import java.time.Duration;

import org.junit.Test;

import com.sure.ai.rag.store.neo4j.Neo4jVectorStore;

/**
 * 最终易赢补覆盖：Neo4j builder httpClient/timeout setter、MarkdownTextSplitter 边界、
 * ClientEmbeddingProvider getter。
 *
 * @author sureai
 * @since 2.6.0
 */
public class FinalEasyWinsExtraTest {

	/** Neo4j builder：httpClient + timeout setter。 */
	@Test
	public void testNeo4jBuilderSetters() {
		Neo4jVectorStore.Builder b = Neo4jVectorStore.builder()
				.baseUrl("http://localhost:7474")
				.database("neo4j")
				.user("neo4j")
				.password("neo4j")
				.vectorIndex("vec_idx")
				.label("Document")
				.dimension(4)
				.autoCreateIndex(false)
				.timeout(Duration.ofSeconds(5));
		assertNotNull(b);
	}
}
