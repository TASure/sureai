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

package com.sure.ai.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link CliRunner} 单元测试：零真实网络，全部用 Fake 客户端与注入流。
 *
 * @author sureai
 * @since 2.0.0
 */
public class CliRunnerTest {

	private ByteArrayOutputStream buffer;
	private PrintStream out;

	@Before
	public void setUp() {
		this.buffer = new ByteArrayOutputStream();
		this.out = new PrintStream(buffer, false, StandardCharsets.UTF_8);
	}

	private String output() {
		out.flush();
		return buffer.toString(StandardCharsets.UTF_8);
	}

	/** 构造使用指定 Fake 客户端的工厂。 */
	private static ClientFactory factoryFor(AiClient client, String defaultModel) {
		ProviderDescriptor desc = new ProviderDescriptor("openai", "OpenAI",
			defaultModel, "text-embed", "SURE_AI_OPENAI_API_KEY", true, (k, u, e) -> client);
		return global -> new Prepared(desc, client,
			(global.model() == null || global.model().isBlank()) ? defaultModel : global.model());
	}

	// ---------- 1. 无参数 / 帮助 ----------

	@Test
	public void noArgsPrintsHelpExit0() {
		CliRunner runner = new CliRunner(out, factoryFor(new FakeAiClient("x", List.of()), "m"));
		int code = runner.run(new String[0]);
		assertEquals(0, code);
		assertTrue(output().contains("用法"));
	}

	@Test
	public void helpFlagPrintsHelpExit0() {
		CliRunner runner = new CliRunner(out, factoryFor(new FakeAiClient("x", List.of()), "m"));
		int code = runner.run(new String[] { "--help" });
		assertEquals(0, code);
		assertTrue(output().contains("子命令"));
	}

	// ---------- 2. 参数解析 / 用法错误 ----------

	@Test
	public void unknownSubcommandReturnsUsage() {
		CliRunner runner = new CliRunner(out, factoryFor(new FakeAiClient("x", List.of()), "m"));
		int code = runner.run(new String[] { "frobnicate", "hi" });
		assertEquals(1, code);
		assertTrue(output().contains("未知子命令"));
	}

	@Test
	public void unknownGlobalOptionReturnsUsage() {
		CliRunner runner = new CliRunner(out, factoryFor(new FakeAiClient("x", List.of()), "m"));
		int code = runner.run(new String[] { "--bogus", "chat", "hi" });
		assertEquals(1, code);
		assertTrue(output().contains("未知全局选项"));
	}

	@Test
	public void chatWithoutQuestionReturnsUsage() {
		CliRunner runner = new CliRunner(out, factoryFor(new FakeAiClient("x", List.of()), "m"));
		int code = runner.run(new String[] { "chat" });
		assertEquals(1, code);
		assertTrue(output().contains("缺少问题文本"));
	}

	// ---------- 3. list ----------

	@Test
	public void listPrintsAllPlatforms() {
		CliRunner runner = new CliRunner(out, factoryFor(new FakeAiClient("x", List.of()), "m"));
		int code = runner.run(new String[] { "list" });
		assertEquals(0, code);
		String text = output();
		assertTrue(text.contains("openai"));
		assertTrue(text.contains("deepseek"));
		assertTrue(text.contains("bedrock"));
		assertTrue(text.contains("spark"));
		assertTrue(text.contains("共 23 个平台"));
	}

	// ---------- 4. chat ----------

	@Test
	public void chatPrintsFixedReply() {
		FakeAiClient client = new FakeAiClient("你好，我是 Fake", List.of());
		CliRunner runner = new CliRunner(out, factoryFor(client, "gpt-4o-mini"));
		int code = runner.run(new String[] { "chat", "打招呼" });
		assertEquals(0, code);
		assertTrue(output().contains("你好，我是 Fake"));
	}

	@Test
	public void chatUsesDefaultModel() {
		FakeAiClient client = new FakeAiClient("ans", List.of());
		CliRunner runner = new CliRunner(out, factoryFor(client, "gpt-4o-mini"));
		runner.run(new String[] { "chat", "q" });
		assertEquals("gpt-4o-mini", client.lastRequest.model());
	}

	@Test
	public void modelOverrideIsPassed() {
		FakeAiClient client = new FakeAiClient("ans", List.of());
		CliRunner runner = new CliRunner(out, factoryFor(client, "gpt-4o-mini"));
		runner.run(new String[] { "--model", "gpt-4o", "chat", "q" });
		assertEquals("gpt-4o", client.lastRequest.model());
	}

	// ---------- 5. stream ----------

	@Test
	public void streamConcatenatesChunks() {
		FakeAiClient client = new FakeAiClient("x", List.of("你", "好", "呀"));
		CliRunner runner = new CliRunner(out, factoryFor(client, "m"));
		int code = runner.run(new String[] { "stream", "问题" });
		assertEquals(0, code);
		assertTrue(output().contains("你好呀"));
	}

	// ---------- 6. 平台选择 / 凭证 ----------

	@Test
	public void unknownProviderReportsError() {
		Map<String, String> env = new HashMap<>();
		ClientFactory real = new DefaultClientFactory(env::get);
		CliRunner runner = new CliRunner(out, real);
		int code = runner.run(new String[] { "--provider", "nope", "chat", "hi" });
		assertEquals(1, code);
		assertTrue(output().contains("未知平台: nope"));
	}

	@Test
	public void missingApiKeyErrorNamesPlatformAndEnv() {
		Map<String, String> env = new HashMap<>();
		ClientFactory real = new DefaultClientFactory(env::get);
		CliRunner runner = new CliRunner(out, real);
		int code = runner.run(new String[] { "chat", "hi" });
		assertEquals(1, code);
		String text = output();
		assertTrue(text.contains("openai"));
		assertTrue(text.contains("SURE_AI_OPENAI_API_KEY"));
	}

	@Test
	public void providerDeepseekSelectsRightClient() {
		// 真实注册表：deepseek 已登记，给出 apiKey 后应能构造出 DeepSeekClient（不发起网络）
		Map<String, String> env = new HashMap<>();
		env.put("SURE_AI_DEEPSEEK_API_KEY", "sk-test");
		ClientFactory real = new DefaultClientFactory(env::get);
		Prepared p = real.prepare(new GlobalOptions("deepseek", null, null, null));
		assertEquals("deepseek", p.client().name());
		assertEquals("deepseek-chat", p.model());
	}

	// ---------- 7. RAG ----------

	@Test
	public void ragIngestsDocumentAndInjectsContext() {
		FakeAiClient client = new FakeAiClient("答：2010 年", List.of());
		String doc = "sureai 是一个零第三方依赖的 Java 库。它支持流式对话与 RAG 检索增强问答。";
		ClientFactory factory = factoryFor(client, "gpt-4o-mini");
		CliRunner runner = new CliRunner(out, factory, path -> doc, () -> null);
		int code = runner.run(new String[] { "rag", "sureai 支持什么？", "--doc", "doc.txt" });
		assertEquals(0, code);
		String text = output();
		assertTrue(text.contains("[rag] 已摄入"));
		assertTrue(text.contains("答：2010 年"));
		// 检索到的文档上下文应进入系统提示词
		ChatMessage systemMsg = client.lastRequest.messages().get(0);
		assertTrue(systemMsg.content().contains("流式对话"));
	}

	@Test
	public void ragWithoutDocFlagReturnsUsage() {
		FakeAiClient client = new FakeAiClient("x", List.of());
		CliRunner runner = new CliRunner(out, factoryFor(client, "m"));
		int code = runner.run(new String[] { "rag", "问题" });
		assertEquals(1, code);
		assertTrue(output().contains("--doc"));
	}

	// ---------- 8. API 异常 ----------

	@Test
	public void apiExceptionReturnsCode2() {
		AiClient boom = new AiClient() {
			public String name() { return "boom"; }
			public ChatResponse chat(ChatRequest r) { throw new IllegalStateException("network down"); }
			public void chatStream(ChatRequest r, Consumer<ChatStreamChunk> c) { }
			public void close() { }
		};
		CliRunner runner = new CliRunner(out, factoryFor(boom, "m"));
		int code = runner.run(new String[] { "chat", "hi" });
		assertEquals(2, code);
		assertTrue(output().contains("[API 异常]"));
	}

	// ---------- 9. repl ----------

	@Test
	public void replAnswersThenStopsOnExit() {
		FakeAiClient client = new FakeAiClient("回答A", List.of());
		List<String> lines = new ArrayList<>(List.of("第一个问题", "exit"));
		CliRunner runner = new CliRunner(out, factoryFor(client, "m"), p -> "",
			() -> lines.isEmpty() ? null : lines.remove(0));
		int code = runner.run(new String[] { "repl" });
		assertEquals(0, code);
		assertTrue(output().contains("回答A"));
	}

	/**
	 * 本地 Fake 客户端：chat 返回固定文本，chatStream 逐片推送，embed 返回字符哈希向量。
	 */
	static final class FakeAiClient implements AiClient, EmbeddingClient {

		private final String reply;
		private final List<String> streamChunks;
		private ChatRequest lastRequest;

		FakeAiClient(String reply, List<String> streamChunks) {
			this.reply = reply;
			this.streamChunks = streamChunks;
		}

		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.lastRequest = request;
			ChatMessage msg = ChatMessage.assistant(reply);
			Choice choice = Choice.of(0, msg, "stop");
			return ChatResponse.of("fake-id", "fake-model", List.of(choice), null, "{}");
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
			this.lastRequest = request;
			for (String chunk : streamChunks) {
				consumer.accept(ChatStreamChunk.of("fake-id", null, chunk, null, null));
			}
		}

		@Override
		public void close() {
		}

		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			List<float[]> vectors = new ArrayList<>();
			for (String text : request.input()) {
				vectors.add(vectorOf(text));
			}
			return EmbeddingResponse.of(request.model(), vectors, null);
		}

		/** 字符哈希向量：相同字符重叠越多余弦相似度越高，用于离线检索验证。 */
		private static float[] vectorOf(String text) {
			float[] v = new float[64];
			for (int i = 0; i < text.length(); i++) {
				int idx = Math.abs(text.charAt(i) * 31 + i) % v.length;
				v[idx] += 1.0f;
			}
			return v;
		}
	}
}
