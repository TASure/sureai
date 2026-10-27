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

package com.sure.ai.rag.graph;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * GraphRAG 构建管线：文档 → 实体/关系抽取入图 → 社区发现 → 逐社区摘要。
 *
 * <p>{@link #ingest(List)} 完成后，可通过 {@link #communities()} 取得带摘要的社区列表，
 * 再交由 {@link GraphRagRetriever} 按社区检索。摘要在内存中持有；本管线与向量检索互补：
 * GraphRAG 提供全局主题级上下文，向量检索提供局部细节片段。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class GraphRagIndexer {

	private final KnowledgeGraph graph;
	private final EntityRelationExtractor extractor;
	private final CommunityDetector detector;
	private final CommunitySummarizer summarizer;

	private volatile List<GraphCommunity> communities = List.of();
	private volatile List<Document> sourceDocs = List.of();

	private GraphRagIndexer(Builder builder) {
		this.graph = builder.graph == null ? new KnowledgeGraph() : builder.graph;
		this.extractor = builder.extractor;
		this.detector = builder.detector == null
				? new LabelPropagationCommunityDetector() : builder.detector;
		this.summarizer = builder.summarizer;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 摄入文档全集：逐篇抽取入图，再做社区发现与逐社区摘要。
	 *
	 * @param documents 文档列表
	 */
	public void ingest(List<Document> documents) {
		Assert.notNull(documents, "documents 不能为 null");
		List<Document> docs = List.copyOf(documents);
		for (Document doc : docs) {
			this.extractor.extractInto(doc, this.graph);
		}
		List<GraphCommunity> detected = this.detector.detect(this.graph);
		List<GraphCommunity> enriched = new ArrayList<>(detected.size());
		for (GraphCommunity community : detected) {
			String summary = this.summarizer.summarize(community, this.graph, docs);
			enriched.add(new GraphCommunity(community.communityId(), community.entityIds(), summary));
		}
		this.sourceDocs = docs;
		this.communities = List.copyOf(enriched);
	}

	/**
	 * 知识图谱。
	 *
	 * @return 图谱
	 */
	public KnowledgeGraph graph() {
		return this.graph;
	}

	/**
	 * 摄入的来源文档。
	 *
	 * @return 文档列表
	 */
	public List<Document> sourceDocs() {
		return this.sourceDocs;
	}

	/**
	 * 带摘要的社区列表（{@link #ingest} 后可用）。
	 *
	 * @return 社区列表快照
	 */
	public List<GraphCommunity> communities() {
		return this.communities;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private KnowledgeGraph graph;
		private EntityRelationExtractor extractor;
		private CommunityDetector detector;
		private CommunitySummarizer summarizer;

		private Builder() {
		}

		/**
		 * 复用既有知识图谱（可选，默认新建空图）。
		 *
		 * @param graph 知识图谱
		 * @return this
		 */
		public Builder graph(KnowledgeGraph graph) {
			this.graph = graph;
			return this;
		}

		/**
		 * 设置实体/关系抽取器（必填）。
		 *
		 * @param extractor 抽取器
		 * @return this
		 */
		public Builder extractor(EntityRelationExtractor extractor) {
			this.extractor = extractor;
			return this;
		}

		/**
		 * 设置社区发现器（可选，默认标签传播）。
		 *
		 * @param detector 社区发现器
		 * @return this
		 */
		public Builder detector(CommunityDetector detector) {
			this.detector = detector;
			return this;
		}

		/**
		 * 设置社区摘要器（必填）。
		 *
		 * @param summarizer 摘要器
		 * @return this
		 */
		public Builder summarizer(CommunitySummarizer summarizer) {
			this.summarizer = summarizer;
			return this;
		}

		/**
		 * 构建管线。
		 *
		 * @return 管线
		 */
		public GraphRagIndexer build() {
			Assert.notNull(this.extractor, "extractor 不能为 null");
			Assert.notNull(this.summarizer, "summarizer 不能为 null");
			return new GraphRagIndexer(this);
		}
	}
}
