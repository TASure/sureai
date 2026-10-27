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

package com.sure.ai.framework.advisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

import org.junit.Test;

/**
 * {@link AdvisorChain} 三钩子顺序语义的零网络测试。
 *
 * @author sureai
 * @since 2.5.0
 */
public class AdvisorChainTest {

	/** 记录事件的探针 advisor。 */
	private static final class Probe implements Advisor {
		private final String name;
		private final List<String> events;

		Probe(String name, List<String> events) {
			this.name = name;
			this.events = events;
		}

		@Override
		public void before(AdvisorContext ctx) {
			this.events.add(this.name + ".before");
		}

		@Override
		public ChatResponse around(AdvisorChain chain, AdvisorContext ctx) {
			this.events.add(this.name + ".around:enter");
			ChatResponse r = chain.proceed(ctx);
			this.events.add(this.name + ".around:exit");
			return r;
		}

		@Override
		public void after(AdvisorContext ctx) {
			this.events.add(this.name + ".after");
		}
	}

	private static AdvisorContext ctx() {
		ChatRequest req = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi"))).build();
		return new AdvisorContext(req, null, null);
	}

	@Test
	public void beforeForwardAroundNestedAfterReverse() {
		List<String> events = new ArrayList<>();
		Advisor a = new Probe("A", events);
		Advisor b = new Probe("B", events);
		AdvisorChain chain = new AdvisorChain(List.of(a, b), c -> ScriptedClient.text("done"));

		ChatResponse resp = chain.execute(ctx());

		assertEquals("done", resp.firstText());
		assertEquals(List.of(
			"A.before", "B.before",
			"A.around:enter", "B.around:enter",
			"B.around:exit", "A.around:exit",
			"B.after", "A.after"), events);
	}

	@Test
	public void shortCircuitSkipsInnerAroundButStillRunsLifecycleHooks() {
		List<String> events = new ArrayList<>();
		ChatResponse cached = ScriptedClient.text("cached");
		Advisor outer = new Advisor() {
			@Override
			public void before(AdvisorContext ctx) {
				events.add("outer.before");
			}

			@Override
			public ChatResponse around(AdvisorChain chain, AdvisorContext ctx) {
				events.add("outer.shortcircuit");
				return cached;
			}

			@Override
			public void after(AdvisorContext ctx) {
				events.add("outer.after");
			}
		};
		Advisor inner = new Probe("inner", events);
		AtomicBoolean terminalCalled = new AtomicBoolean();
		AdvisorChain chain = new AdvisorChain(List.of(outer, inner), c -> {
			terminalCalled.set(true);
			return ScriptedClient.text("should-not-happen");
		});

		ChatResponse resp = chain.execute(ctx());

		assertSame(cached, resp);
		assertFalse("短路后终端不应被调用", terminalCalled.get());
		// before 正序跑过 outer 与 inner；around 短路未进 inner；after 逆序仍跑 inner、outer
		assertEquals(List.of(
			"outer.before", "inner.before",
			"outer.shortcircuit",
			"inner.after", "outer.after"), events);
	}

	@Test
	public void afterRunsEvenWhenTerminalThrows() {
		List<String> events = new ArrayList<>();
		Advisor a = new Probe("A", events);
		AdvisorChain chain = new AdvisorChain(List.of(a), c -> {
			throw new RuntimeException("boom");
		});
		try {
			chain.execute(ctx());
		} catch (RuntimeException e) {
			assertEquals("boom", e.getMessage());
		}
		assertEquals(List.of("A.before", "A.around:enter", "A.after"), events);
	}
}
