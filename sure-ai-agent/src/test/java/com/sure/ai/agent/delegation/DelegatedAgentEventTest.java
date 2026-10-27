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

package com.sure.ai.agent.delegation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.sure.ai.agent.event.ThoughtEvent;
import org.junit.Test;

/**
 * {@link DelegatedAgentEvent} 包装元数据测试。
 */
public class DelegatedAgentEventTest {

	@Test
	public void wrapsDelegateeAndCarriesPathMetadata() {
		ThoughtEvent inner = new ThoughtEvent("child", "子思考", 12345L);
		DelegatedAgentEvent wrapped = DelegatedAgentEvent.wrap(inner, "sub_a", 1,
			List.of("root", "parent"));

		assertSame(inner, wrapped.delegatee());
		assertEquals("sub_a", wrapped.sourceAgentId());
		assertEquals(1, wrapped.depth());
		assertEquals(List.of("root", "parent"), wrapped.ancestorIds());
		// 类型与时间戳透传原始事件
		assertEquals("thought", wrapped.type());
		assertEquals(12345L, wrapped.timestampEpochMs());
	}

	@Test
	public void nullAncestorListDefensivelyBecomesEmpty() {
		ThoughtEvent inner = new ThoughtEvent("c", "t", 1L);
		DelegatedAgentEvent wrapped = new DelegatedAgentEvent(inner, "c", 0, null);
		assertTrue(wrapped.ancestorIds().isEmpty());
	}

	@Test
	public void ancestorListIsImmutableCopy() {
		java.util.List<String> mutable = new java.util.ArrayList<>(List.of("a"));
		DelegatedAgentEvent wrapped = new DelegatedAgentEvent(
			new ThoughtEvent("c", "t", 1L), "c", 0, mutable);
		mutable.add("b");
		assertEquals(1, wrapped.ancestorIds().size());
	}
}
