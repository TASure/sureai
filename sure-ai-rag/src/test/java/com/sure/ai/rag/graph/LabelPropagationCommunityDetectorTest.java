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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * 标签传播社区发现器测试。
 */
public class LabelPropagationCommunityDetectorTest {

	private final LabelPropagationCommunityDetector detector = new LabelPropagationCommunityDetector();

	@Test
	public void testTwoDisconnectedSubgraphsDetected() {
		KnowledgeGraph graph = new KnowledgeGraph();
		// 连通分量 A：a-b-c
		graph.addRelation("a", "b", "r", null);
		graph.addRelation("b", "c", "r", null);
		// 连通分量 B：x-y
		graph.addRelation("x", "y", "r", null);

		List<GraphCommunity> communities = detector.detect(graph);

		assertEquals(2, communities.size());
		int sizeA = communities.get(0).entityIds().size();
		int sizeB = communities.get(1).entityIds().size();
		assertTrue((sizeA == 3 && sizeB == 2) || (sizeA == 2 && sizeB == 3));
	}

	@Test
	public void testConnectedGraphIsOneCommunity() {
		KnowledgeGraph graph = new KnowledgeGraph();
		graph.addRelation("a", "b", "r", null);
		graph.addRelation("b", "c", "r", null);
		graph.addRelation("c", "a", "r", null);

		List<GraphCommunity> communities = detector.detect(graph);

		assertEquals(1, communities.size());
		assertEquals(3, communities.get(0).entityIds().size());
	}

	@Test
	public void testIsolatedEntityBecomesSingletonCommunity() {
		KnowledgeGraph graph = new KnowledgeGraph();
		graph.addRelation("a", "b", "r", null);
		graph.addEntity("lonely", null, null);

		List<GraphCommunity> communities = detector.detect(graph);

		assertEquals(2, communities.size());
		boolean foundSingleton = communities.stream().anyMatch(c ->
				c.entityIds().size() == 1 && c.entityIds().contains("lonely"));
		assertTrue(foundSingleton);
	}
}
