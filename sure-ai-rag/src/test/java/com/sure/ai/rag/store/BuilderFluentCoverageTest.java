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
package com.sure.ai.rag.store;

import java.time.Duration;

import org.junit.Test;

/**
 * 各向量库构建器 fluent setter 覆盖：逐一调用每个 setter，确保未启用自动建表/建索引
 * （避免触发真实网络），仅覆盖构建器方法本身的行，零外部依赖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class BuilderFluentCoverageTest {

	private static final Duration T = Duration.ofSeconds(1);

	/** Elasticsearch 构建器全量 setter。 */
	@Test
	public void elasticsearchSetters() {
		ElasticsearchVectorStore.builder().baseUrl("http://x:9200").apiKey("k").indexName("idx")
				.vectorField("vec").textField("txt").dimension(4).autoCreateIndex(false).timeout(T)
				.build();
	}

	/** Milvus 构建器全量 setter。 */
	@Test
	public void milvusSetters() {
		MilvusVectorStore.builder().baseUrl("http://x:19530").apiKey("k").collectionName("c")
				.dimension(4).metricType(MilvusVectorStore.MetricType.COSINE).autoCreateCollection(false).timeout(T).build();
	}

	/** OpenSearch 构建器全量 setter。 */
	@Test
	public void openSearchSetters() {
		OpenSearchVectorStore.builder().baseUrl("http://x:9200").apiKey("k").indexName("idx")
				.vectorField("vec").textField("txt").dimension(4).autoCreateIndex(false).timeout(T)
				.build();
	}

	/** Pinecone 构建器全量 setter。 */
	@Test
	public void pineconeSetters() {
		PineconeVectorStore.builder().baseUrl("http://x").apiKey("k").namespace("ns").timeout(T)
				.build();
	}

	/** Qdrant 构建器全量 setter。 */
	@Test
	public void qdrantSetters() {
		QdrantVectorStore.builder().baseUrl("http://x:6333").apiKey("k").collectionName("c")
				.dimension(4).distance(QdrantVectorStore.Distance.COSINE).autoCreateCollection(false).timeout(T).build();
	}

	/** Weaviate 构建器全量 setter。 */
	@Test
	public void weaviateSetters() {
		WeaviateVectorStore.builder().baseUrl("http://x:8080").apiKey("k").className("C")
				.textProperty("txt").autoCreateSchema(false).timeout(T).build();
	}
}
