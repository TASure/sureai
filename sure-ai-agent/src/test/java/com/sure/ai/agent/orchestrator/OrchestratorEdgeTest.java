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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.Test;

import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;

/**
 * {@link AgentOrchestrator} 与 {@link SimpleTaskSplitter} 边界补充测试。
 *
 * <p>覆盖空子任务聚合、自建线程池关闭、重复关闭、agentFactory 空校验、
 * 自定义拆分器注入与按数量拆分时的越界跳过。</p>
 */
public class OrchestratorEdgeTest {

	/** 回显用户消息的假客户端，零网络。 */
	static final class EchoClient implements AiClient {
		@Override
		public String name() {
			return "echo";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String last = "";
			for (ChatMessage m : request.messages()) {
				if (m.role() == Role.USER && m.content() != null) {
					last = m.content();
				}
			}
			return ChatResponse.of("id", "fake-model",
					List.of(Choice.of(0, ChatMessage.assistant(last), "stop")),
					TokenUsage.of(1, 1, 2), null);
		}

		@Override
		public void chatStream(ChatRequest request,
				java.util.function.Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}

	private static ChatRequest baseRequest() {
		return ChatRequest.builder().model("m").messages(ChatMessage.system("s")).build();
	}

	@Test
	public void testEmptySubtasksAggregatesEmpty() {
		try (AgentOrchestrator orch = AgentOrchestrator.builder(
				sub -> new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry()))
			.build()) {
			String result = orch.execute(List.of());
			assertTrue(result.isEmpty());
		}
	}

	@Test
	public void testBuildCreatesInternalPoolAndCloseIsIdempotent() {
		AgentOrchestrator orch = AgentOrchestrator.builder(
				sub -> new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry()))
			.timeout(Duration.ofSeconds(5))
			.build();
		assertEquals("x", orch.execute(List.of("x")));
		orch.close();
		orch.close(); // 重复关闭直接返回
	}

	@Test
	public void testRejectsNullFactory() {
		assertThrows(IllegalArgumentException.class, () -> AgentOrchestrator.builder(null));
	}

	@Test
	public void testCustomSplitterInjected() {
		TaskSplitter custom = task -> List.of("一", "二");
		try (AgentOrchestrator orch = AgentOrchestrator.builder(
				sub -> new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry()))
			.splitter(custom)
			.build()) {
			String result = orch.execute("任意任务文本");
			assertTrue(result, result.contains("一"));
			assertTrue(result, result.contains("二"));
		}
	}

	@Test
	public void testTimeoutCancelsSlowSubtask() {		// 极短整体超时 + 慢子任务 → invokeAll 取消未完成 future → CancellationException
		try (AgentOrchestrator orch = AgentOrchestrator.builder(sub -> {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry());
		}).timeout(Duration.ofMillis(50)).build()) {
			String result = orch.execute(List.of("slow-one"));
			assertTrue(result, result.contains("[ERROR:"));
		}
	}

	@Test
	public void testInvokeAllInterrupted() throws Exception {
		// 子任务阻塞；60ms 后中断当前线程 → invokeAll 抛 InterruptedException
		try (AgentOrchestrator orch = AgentOrchestrator.builder(sub -> {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry());
		}).timeout(Duration.ofSeconds(10)).build()) {
			Thread self = Thread.currentThread();
			java.util.concurrent.ScheduledExecutorService sched =
					java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
			sched.schedule(self::interrupt, 60, java.util.concurrent.TimeUnit.MILLISECONDS);
			try {
				String result = orch.execute(List.of("slow"));
				assertTrue(result, result.contains("orchestrator interrupted"));
			} finally {
				sched.shutdownNow();
			}
		}
	}

	@Test
	public void testEvenCountSplitterSkipsOversizeChunks() {		// count=5 但文本很短 → 只有前若干段，越界 chunk 被跳过
		SimpleTaskSplitter splitter = new SimpleTaskSplitter(SimpleTaskSplitter.SplitStrategy.EVEN_COUNT, 5);
		List<String> parts = splitter.split("abc");
		// size=1，"abc" 切成 a/b/c，剩余 chunk 越界被跳过
		assertEquals(3, parts.size());
		assertEquals("a", parts.get(0));
		assertEquals("c", parts.get(2));
	}
}
