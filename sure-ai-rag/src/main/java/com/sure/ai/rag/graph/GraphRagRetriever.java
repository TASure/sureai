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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.tool.lang.Assert;

/**
 * GraphRAG 检索器：按 query 命中的实体定位所属社区，返回社区摘要作为上下文。
 *
 * <p>检索路径（确定性、零额外 LLM 调用）：</p>
 * <ol>
 *   <li>把 query 规范化（转小写、去空白）；</li>
 *   <li>字面/子串匹配图谱中的实体名（规范化实体 id 是否为 query 子串）；</li>
 *   <li>统计每个社区被命中的实体数；</li>
 *   <li>按命中数降序、社区编号升序排序，取前 topK 个社区；</li>
 *   <li>把每个社区摘要包装为 {@link Document} 返回。</li>
 * </ol>
 *
 * <p>无命中时返回空列表，由调用方结合向量检索降级；GraphRAG 提供主题级全局上下文，
 * 与向量检索的局部细节互补。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class GraphRagRetriever implements Retriever {

	private final KnowledgeGraph graph;
	private final List<GraphCommunity> communities;
	private final Map<String, Integer> entityToCommunity;

	private GraphRagRetriever(Builder builder) {
		this.graph = builder.indexer.graph();
		this.communities = List.copyOf(builder.indexer.communities());
		Map<String, Integer> map = new HashMap<>();
		for (GraphCommunity community : this.communities) {
			for (String entityId : community.entityIds()) {
				map.put(entityId, community.communityId());
			}
		}
		this.entityToCommunity = map;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public List<Document> retrieve(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);

		String normalizedQuery = KnowledgeGraph.normalizeName(query);
		if (normalizedQuery.isEmpty()) {
			return List.of();
		}

		// 统计每个社区命中的实体数
		Map<Integer, Integer> hits = new HashMap<>();
		for (GraphEntity entity : this.graph.entities()) {
			if (normalizedQuery.contains(entity.id())) {
				Integer communityId = this.entityToCommunity.get(entity.id());
				if (communityId != null) {
					hits.merge(communityId, 1, Integer::sum);
				}
			}
		}
		if (hits.isEmpty()) {
			return List.of();
		}

		// 命中数降序，社区编号升序
		List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(hits.entrySet());
		ranked.sort(Comparator
				.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue).reversed()
				.thenComparingInt(Map.Entry::getKey));

		List<Document> result = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : ranked) {
			if (result.size() >= topK) {
				break;
			}
			GraphCommunity community = findCommunity(entry.getKey());
			if (community != null) {
				Map<String, String> metadata = new LinkedHashMap<>();
				metadata.put("communityId", String.valueOf(community.communityId()));
				metadata.put("hitEntities", String.valueOf(entry.getValue()));
				result.add(Document.of("graph-community-" + community.communityId(),
						community.summary() == null ? "" : community.summary(), metadata));
			}
		}
		return result;
	}

	private GraphCommunity findCommunity(int communityId) {
		for (GraphCommunity community : this.communities) {
			if (community.communityId() == communityId) {
				return community;
			}
		}
		return null;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private GraphRagIndexer indexer;

		private Builder() {
		}

		/**
		 * 设置已完成 ingest 的 GraphRAG 管线。
		 *
		 * @param indexer 管线
		 * @return this
		 */
		public Builder indexer(GraphRagIndexer indexer) {
			this.indexer = indexer;
			return this;
		}

		/**
		 * 构建检索器。
		 *
		 * @return 检索器
		 */
		public GraphRagRetriever build() {
			Assert.notNull(this.indexer, "indexer 不能为 null");
			return new GraphRagRetriever(this);
		}
	}
}
