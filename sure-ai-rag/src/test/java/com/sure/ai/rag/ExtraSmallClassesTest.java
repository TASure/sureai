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
package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.graph.KnowledgeGraph;
import com.sure.ai.rag.strategy.MultimodalDocument;

/**
 * 极小类补覆盖测试：KnowledgeGraph.normalizeName(null)、MultimodalDocument.of 工厂。
 *
 * @author sureai
 * @since 2.6.0
 */
public class ExtraSmallClassesTest {

	/** normalizeName(null) → 空串。 */
	@Test
	public void testNormalizeNameNull() {
		assertEquals("", KnowledgeGraph.normalizeName(null));
	}

	/** MultimodalDocument.of 工厂。 */
	@Test
	public void testMultimodalDocumentOf() {
		MultimodalDocument doc = MultimodalDocument.of("id1", List.of(), Map.of("k", "v"));
		assertNotNull(doc);
		assertEquals("id1", doc.id());
	}
}
