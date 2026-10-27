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

package com.sure.ai.evals.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.EvalException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.ToolCall;

/**
 * {@link RecordingClient} 录制 / 回放 / 生命周期 / 边界测试（零真实网络）。
 */
public class RecordingClientTest {

	private static ChatRequest requestOf(String model, String prompt) {
		return ChatRequest.builder()
			.model(model)
			.messages(ChatMessage.user(prompt))
			.build();
	}

	@Test
	public void 录制落盘后回放文本与usage一致() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient("答案A", "答案B");
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			assertEquals("recording(script)", recording.name());
			assertEquals("答案A", recording.chat(requestOf("m", "q1")).firstText());
			assertEquals("答案B", recording.chat(requestOf("m", "q2")).firstText());
		}
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		assertEquals(2, lines.size());

		try (RecordingClient replay = RecordingClient.replay(file)) {
			assertEquals("replay", replay.name());
			ChatResponse r1 = replay.chat(requestOf("m", "q1"));
			ChatResponse r2 = replay.chat(requestOf("m", "q2"));
			assertEquals("答案A", r1.firstText());
			assertEquals("答案B", r2.firstText());
			assertEquals(5, r1.usage().totalTokens());
			assertEquals(3, r2.usage().completionTokens());
		}
		// 录制期间委托被真实调用两次；回放模式不持有委托、不再触碰脚本
		assertEquals(2, script.calls);
	}

	@Test
	public void 录制文件即回放时按请求匹配() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient("A", "B", "C");
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			recording.chat(requestOf("m", "q1"));
			recording.chat(requestOf("m", "q2"));
			recording.chat(requestOf("m", "q3"));
		}
		try (RecordingClient replay = RecordingClient.replay(file)) {
			// 打乱顺序回放：按请求内容匹配
			assertEquals("B", replay.chat(requestOf("m", "q2")).firstText());
			assertEquals("A", replay.chat(requestOf("m", "q1")).firstText());
			assertEquals("C", replay.chat(requestOf("m", "q3")).firstText());
		}
	}

	@Test
	public void 回放耗尽抛运行期异常() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient("唯一");
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			recording.chat(requestOf("m", "q1"));
		}
		try (RecordingClient replay = RecordingClient.replay(file)) {
			assertNotNull(replay.chat(requestOf("m", "q1")));
			assertThrows(RuntimeException.class, () -> replay.chat(requestOf("m", "q2")));
			assertThrows(RuntimeException.class, () -> replay.chatStream(requestOf("m", "q3"), c -> {
			}));
		}
	}

	@Test
	public void 空录制文件首次调用即耗尽() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		try (RecordingClient replay = RecordingClient.replay(file)) {
			assertThrows(RuntimeException.class, () -> replay.chat(requestOf("m", "q1")));
		}
	}

	@Test
	public void 坏JSONL行给清晰错误() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		Files.write(file, List.of(
			"{\"id\":\"x\",\"request\":{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]},\"response\":{\"id\":\"r\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}}",
			"这一行根本不是JSON"));
		RuntimeException e = assertThrows(EvalException.class, () -> RecordingClient.replay(file));
		assertTrue(e.getMessage().contains("第 2 行"));
	}

	@Test
	public void Writer模式只flush不关闭() {
		ScriptClient script = new ScriptClient("A");
		StringWriter writer = new StringWriter();
		try (RecordingClient recording = RecordingClient.record(script, writer)) {
			recording.chat(requestOf("m", "q1"));
		}
		String content = writer.toString();
		assertTrue(content.contains("\"content\":\"A\""));
		// Writer 工厂默认不关闭委托
		assertEquals(0, script.closes);

		// 从内存回放
		try (RecordingClient replay = RecordingClient.replay(new StringReader(content))) {
			assertEquals("A", replay.chat(requestOf("m", "q1")).firstText());
		}
	}

	@Test
	public void 委托关闭策略可控() throws IOException {
		Path file1 = Files.createTempFile("rec", ".jsonl");
		ScriptClient delegated = new ScriptClient("A");
		try (RecordingClient recording = RecordingClient.record(delegated, file1, true)) {
			recording.chat(requestOf("m", "q1"));
		}
		assertEquals(1, delegated.closes);

		Path file2 = Files.createTempFile("rec", ".jsonl");
		ScriptClient notClosed = new ScriptClient("A");
		try (RecordingClient recording = RecordingClient.record(notClosed, file2)) {
			recording.chat(requestOf("m", "q1"));
		}
		assertEquals(0, notClosed.closes);
	}

	@Test
	public void 流式录制聚合后回放整体投递() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient();
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			StringBuilder out = new StringBuilder();
			recording.chatStream(requestOf("m", "流"), chunk -> out.append(chunk.deltaText()));
			assertEquals("你好世界", out.toString());
		}
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		assertEquals(1, lines.size());

		try (RecordingClient replay = RecordingClient.replay(file)) {
			java.util.List<ChatStreamChunk> out = new java.util.ArrayList<>();
			replay.chatStream(requestOf("m", "流"), out::add);
			assertEquals(1, out.size());
			// 聚合消息含 toolCalls，文本为 null（单消息不同时承载两者）
			assertNull(out.get(0).deltaText());
			assertEquals(1, out.get(0).toolCalls().size());
		}
	}

	@Test
	public void 委托异常原样传播不录制() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient("A").alwaysThrow(new RuntimeException("boom"));
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			assertThrows(RuntimeException.class, () -> recording.chat(requestOf("m", "q1")));
		}
		// 异常响应不落盘
		assertEquals(0, Files.readAllLines(file, StandardCharsets.UTF_8).size());
	}

	@Test
	public void IO失败包装为EvalException() throws IOException {
		// 录制目标是目录：打开失败
		Path dir = Files.createTempDirectory("recdir");
		assertThrows(EvalException.class, () -> RecordingClient.record(new ScriptClient(), dir));
		// 回放文件不存在
		assertThrows(EvalException.class,
			() -> RecordingClient.replay(Path.of("不存在-" + System.nanoTime() + ".jsonl")));
		// Reader 读取失败
		java.io.Reader faulty = new java.io.Reader() {
			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				throw new IOException("read boom");
			}

			@Override
			public void close() {
			}
		};
		assertThrows(EvalException.class, () -> RecordingClient.replay(faulty));
	}

	@Test
	public void 空行被跳过且writer异常包装() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient("A");
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			recording.chat(requestOf("m", "q1"));
		}
		// 追加空行与注释行后回放仍正常
		List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
		lines.add("");
		lines.add("   ");
		Files.write(file, lines, StandardCharsets.UTF_8);
		try (RecordingClient replay = RecordingClient.replay(file)) {
			assertEquals("A", replay.chat(requestOf("m", "q1")).firstText());
		}

		// writer flush 失败 → EvalException
		java.io.Writer boom = new java.io.Writer() {
			@Override
			public void write(char[] cbuf, int off, int len) {
			}

			@Override
			public void flush() throws IOException {
				throw new IOException("flush boom");
			}

			@Override
			public void close() {
			}
		};
		RecordingClient client = RecordingClient.record(new ScriptClient(), boom);
		assertThrows(EvalException.class, client::close);

		// writer 写失败 → EvalException
		java.io.Writer writeBoom = new java.io.Writer() {
			@Override
			public void write(char[] cbuf, int off, int len) throws IOException {
				throw new IOException("write boom");
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		try (RecordingClient bad = RecordingClient.record(new ScriptClient("x"), writeBoom)) {
			assertThrows(EvalException.class, () -> bad.chat(requestOf("m", "q")));
		}
	}

	@Test
	public void 回放兼容带工具调用的多轮请求() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient("工具结果");
		ChatRequest multi = ChatRequest.builder()
			.model("m")
			.messages(ChatMessage.user("查天气"),
				ChatMessage.assistant(List.of(ToolCall.of("c1", "weather", "{}"))),
				ChatMessage.tool("c1", "晴天"))
			.build();
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			assertEquals("工具结果", recording.chat(multi).firstText());
		}
		try (RecordingClient replay = RecordingClient.replay(file)) {
			// 相同多轮请求按内容命中（覆盖 toolCalls 比对分支）
			assertEquals("工具结果", replay.chat(multi).firstText());
			// 模型名不同 → 内容不匹配 → 顺序兜底 → 已耗尽抛错
			ChatRequest otherModel = ChatRequest.builder()
				.model("other-model")
				.messages(multi.messages())
				.build();
			assertThrows(RuntimeException.class, () -> replay.chat(otherModel));
		}
	}

	@Test
	public void 流式回放携带工具调用分片() throws IOException {
		Path file = Files.createTempFile("rec", ".jsonl");
		ScriptClient script = new ScriptClient();
		try (RecordingClient recording = RecordingClient.record(script, file)) {
			recording.chatStream(requestOf("m", "流"), c -> {
			});
		}
		try (RecordingClient replay = RecordingClient.replay(file)) {
			java.util.List<ChatStreamChunk> out = new java.util.ArrayList<>();
			replay.chatStream(requestOf("m", "流"), out::add);
			assertEquals(1, out.size());
			assertNull(out.get(0).deltaText());
			assertEquals(1, out.get(0).toolCalls().size());
			assertEquals("weather", out.get(0).toolCalls().get(0).name());
		}
	}
}
