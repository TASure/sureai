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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 知识图谱模型测试。
 */
public class KnowledgeGraphTest {

	@Test
	public void testAddEntityDedupMerge() {
		KnowledgeGraph graph = new KnowledgeGraph();
		graph.addEntity("Alice", "person", Map.of("sourceDocId", "d1"));
		graph.addEntity("Alice", null, Map.of("role", "ceo"));

		assertEquals(1, graph.entityCount());
		GraphEntity entity = graph.getEntity("alice");
		assertEquals("alice", entity.id());
		assertEquals("Alice", entity.name());
		assertEquals("person", entity.type());
		assertEquals("d1", entity.metadata().get("sourceDocId"));
		assertEquals("ceo", entity.metadata().get("role"));
	}

	@Test
	public void testNameNormalizationMergesCaseAndSpace() {
		KnowledgeGraph graph = new KnowledgeGraph();
		graph.addEntity("Beijing", null, null);
		graph.addEntity(" beijing ", null, null);
		graph.addEntity("Bei jing", null, null);

		assertEquals(1, graph.entityCount());
		assertEquals("beijing", graph.getEntity("beijing").id());
	}

	@Test
	public void testAddRelationNeighborsAndRelationsOf() {
		KnowledgeGraph graph = new KnowledgeGraph();
		graph.addRelation("Alice", "Meta", "worksAt", Map.of("sourceDocId", "d1"));
		graph.addRelation("Alice", "China", "livesIn", null);

		assertEquals(2, graph.relationCount());
		assertEquals(3, graph.entityCount());

		List<GraphEntity> neighbors = graph.neighbors("alice");
		assertEquals(2, neighbors.size());
		assertTrue(neighbors.stream().anyMatch(e -> e.id().equals("meta")));
		assertTrue(neighbors.stream().anyMatch(e -> e.id().equals("china")));

		List<GraphRelation> relationsOfAlice = graph.relationsOf("alice");
		assertEquals(2, relationsOfAlice.size());

		assertTrue(graph.neighbors("bob").isEmpty());
	}

	@Test
	public void testSelfLoopSkipped() {
		KnowledgeGraph graph = new KnowledgeGraph();
		GraphRelation relation = graph.addRelation("Alice", "Alice", "isSelf", null);
		assertNull(relation);
		assertEquals(0, graph.relationCount());
		assertEquals(0, graph.entityCount());
	}

	@Test
	public void testDuplicateRelationMergesMetadata() {
		KnowledgeGraph graph = new KnowledgeGraph();
		graph.addRelation("Alice", "Meta", "worksAt", Map.of("sourceDocId", "d1"));
		graph.addRelation("Alice", "Meta", "worksAt", Map.of("sourceDocId", "d2"));

		assertEquals(1, graph.relationCount());
		GraphRelation relation = graph.relations().get(0);
		assertEquals("d2", relation.metadata().get("sourceDocId"));
	}
}
