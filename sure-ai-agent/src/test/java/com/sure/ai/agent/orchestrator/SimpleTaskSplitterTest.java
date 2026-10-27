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

package com.sure.ai.agent.orchestrator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * {@link SimpleTaskSplitter} 与 {@link ConcatenatingAggregator} 单元测试。
 */
public class SimpleTaskSplitterTest {

	@Test
	public void testParagraphStrategy() {
		SimpleTaskSplitter splitter = new SimpleTaskSplitter();
		List<String> out = splitter.split("one\n\ntwo\n\nthree");
		assertEquals(3, out.size());
		assertEquals("one", out.get(0));
	}

	@Test
	public void testSentenceStrategy() {
		SimpleTaskSplitter splitter =
			new SimpleTaskSplitter(SimpleTaskSplitter.SplitStrategy.SENTENCE);
		List<String> out = splitter.split("第一句。第二句！第三句?done");
		assertTrue(out.size() >= 3);
	}

	@Test
	public void testEvenCountStrategy() {
		SimpleTaskSplitter splitter =
			new SimpleTaskSplitter(SimpleTaskSplitter.SplitStrategy.EVEN_COUNT, 4);
		List<String> out = splitter.split("abcdefgh");
		assertEquals(4, out.size());
		assertEquals("ab", out.get(0));
		assertEquals("gh", out.get(3));
		assertEquals(8, out.get(0).length() + out.get(1).length()
			+ out.get(2).length() + out.get(3).length());
	}

	@Test
	public void testNullOrEmpty() {
		SimpleTaskSplitter splitter = new SimpleTaskSplitter();
		assertTrue(splitter.split(null).isEmpty());
		assertTrue(splitter.split("").isEmpty());
	}

	@Test
	public void testConcatenatingAggregatorDefaults() {
		ConcatenatingAggregator agg = new ConcatenatingAggregator();
		assertEquals("a\n---\nb", agg.aggregate(List.of("a", "b")));
		assertEquals("", agg.aggregate(List.of()));
		assertEquals("x", agg.aggregate(List.of("", "x")));
	}

	@Test
	public void testConcatenatingAggregatorCustom() {
		ConcatenatingAggregator agg = new ConcatenatingAggregator("|");
		assertEquals("x|y|z", agg.aggregate(List.of("x", "y", "z")));
	}
}
