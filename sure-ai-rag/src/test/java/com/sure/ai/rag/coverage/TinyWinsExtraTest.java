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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.rag.graph.GraphEntity;
import com.sure.ai.rag.graph.GraphRelation;
import com.sure.ai.rag.splitter.RecursiveCharacterTextSplitter;

/**
 * 极小类 1 行补覆盖：RecursiveCharacterTextSplitter 返回、GraphRelation/GraphEntity 默认 metadata。
 *
 * @author sureai
 * @since 2.6.0
 */
public class TinyWinsExtraTest {

	/** RecursiveCharacterTextSplitter：空输入返回空列表。 */
	@Test
	public void testRecursiveSplitterEmpty() {
		RecursiveCharacterTextSplitter s = new RecursiveCharacterTextSplitter(100, 10,
				java.util.List.of("\n\n", "\n", " "), true);
		assertTrue(s.split("").isEmpty());
	}

	/** GraphRelation：默认 metadata 为空 map。 */
	@Test
	public void testGraphRelationDefaultMetadata() {
		GraphRelation r = new GraphRelation("src", "tgt", "rel", null);
		assertNotNull(r.metadata());
	}

	/** GraphEntity：默认 metadata 为空 map。 */
	@Test
	public void testGraphEntityDefaultMetadata() {
		GraphEntity e = new GraphEntity("id", "name", "type", null);
		assertNotNull(e.metadata());
	}
}
