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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sure.tool.lang.Assert;

/**
 * 标签传播（Label Propagation）社区发现器——纯 JDK 实现，零第三方依赖。
 *
 * <p>算法：每个实体初始化为唯一标签（自身 id）；迭代地按邻居标签多数投票更新自身标签，
 * 直至标签不再变化或达到最大轮次。关系按无向图处理。平局时按标签字典序取最小者，
 * 保证确定性收敛、不震荡。</p>
 *
 * <p>孤立实体（无任何关系）无邻居可投票，始终保持自身标签，因此归为单点社区；
 * 摘要阶段可按社区大小过滤过小社区。相比 Louvain，标签传播无需计算模块度、
 * 实现简单且对稀疏图稳健。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class LabelPropagationCommunityDetector implements CommunityDetector {

	/** 默认最大迭代轮次。 */
	public static final int DEFAULT_MAX_ITERATIONS = 20;

	private final int maxIterations;

	/**
	 * 使用默认轮次构造。
	 */
	public LabelPropagationCommunityDetector() {
		this(DEFAULT_MAX_ITERATIONS);
	}

	/**
	 * 指定最大迭代轮次构造。
	 *
	 * @param maxIterations 最大迭代轮次，必须大于 0
	 */
	public LabelPropagationCommunityDetector(int maxIterations) {
		Assert.isTrue(maxIterations > 0, "maxIterations 必须大于 0，实际为 {}", maxIterations);
		this.maxIterations = maxIterations;
	}

	@Override
	public List<GraphCommunity> detect(KnowledgeGraph graph) {
		Assert.notNull(graph, "graph 不能为 null");

		// 无向邻接表
		Map<String, List<String>> adjacency = new TreeMap<>();
		for (GraphEntity entity : graph.entities()) {
			adjacency.put(entity.id(), new ArrayList<>());
		}
		for (GraphRelation relation : graph.relations()) {
			String s = relation.sourceId();
			String t = relation.targetId();
			adjacency.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
			adjacency.computeIfAbsent(t, k -> new ArrayList<>()).add(s);
		}

		// 初始标签 = 实体 id 自身
		Map<String, String> labels = new TreeMap<>();
		for (String id : adjacency.keySet()) {
			labels.put(id, id);
		}

		for (int iter = 0; iter < this.maxIterations; iter++) {
			boolean changed = false;
			for (Map.Entry<String, List<String>> adj : adjacency.entrySet()) {
				String id = adj.getKey();
				List<String> neighbors = adj.getValue();
				if (neighbors.isEmpty()) {
					continue;
				}
				// 统计邻居标签频次
				Map<String, Integer> counts = new HashMap<>();
				for (String neighbor : neighbors) {
					String label = labels.get(neighbor);
					counts.merge(label, 1, Integer::sum);
				}
				String best = null;
				int bestCount = -1;
				for (Map.Entry<String, Integer> entry : counts.entrySet()) {
					String label = entry.getKey();
					int count = entry.getValue();
					// 平局取字典序更小者，保证确定性
					if (count > bestCount || (count == bestCount && label.compareTo(best) < 0)) {
						best = label;
						bestCount = count;
					}
				}
				if (best != null && !best.equals(labels.get(id))) {
					labels.put(id, best);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}

		// 按标签分组，组内实体按字典序排序
		Map<String, List<String>> groups = new TreeMap<>();
		for (Map.Entry<String, String> entry : labels.entrySet()) {
			groups.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
		}
		// 组的顺序由最小实体 id 决定（TreeMap 已按标签即最小实体 id 排序）
		List<GraphCommunity> communities = new ArrayList<>();
		int communityId = 0;
		for (List<String> members : groups.values()) {
			communities.add(new GraphCommunity(communityId, members, null));
			communityId++;
		}
		return communities;
	}
}
