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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
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
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * CLI 覆盖补齐：RAG 非嵌入客户端/无默认嵌入模型、REPL 空行与 quit、
 * nameOf 空描述符、本地文件读取失败、DefaultClientFactory 凭证分支、
 * CliException 三参构造、ArgsParser/Command 私有构造与 null 解析、Environment.SYSTEM。
 *
 * <p>零真实网络。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class CliCoverageExtraTest {

	private ByteArrayOutputStream buffer;
	private PrintStream out;

	@Before
	public void setUp() {
		this.buffer = new ByteArrayOutputStream();
		this.out = new PrintStream(this.buffer, false, StandardCharsets.UTF_8);
	}

	private String output() {
		this.out.flush();
		return this.buffer.toString(StandardCharsets.UTF_8);
	}

	/** 构造一个固定回复的 AiClient（可选是否实现 EmbeddingClient）。 */
	private static AiClient chatOnlyClient() {
		return new AiClient() {
			@Override
			public String name() {
				return "fake";
			}

			@Override
			public ChatResponse chat(ChatRequest request) {
				return CliRunnerTestSupport.fixedReply("ans");
			}

			@Override
			public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> c) {
				// 不使用
			}

			@Override
			public void close() {
			}
		};
	}

	/** 构造同时实现 {@link AiClient} 与 {@link EmbeddingClient} 的 fake 客户端。 */
	private static AiClient embeddingClient() {
		return new BothClient();
	}

	/** 同时实现对话与向量的 fake 客户端。 */
	private static final class BothClient implements AiClient, EmbeddingClient {
		@Override
		public String name() {
			return "both";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			return CliRunnerTestSupport.fixedReply("ans");
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> c) {
			// 不使用
		}

		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			return EmbeddingResponse.of(request.model(),
				List.of(new float[] { 0.1f, 0.2f, 0.3f }), null);
		}

		@Override
		public void close() {
		}
	}

	/** RAG：客户端不实现 EmbeddingClient → 退出码 1。 */
	@Test
	public void ragOnNonEmbeddingClientReturnsUsage() {
		AiClient client = chatOnlyClient();
		ClientFactory factory = global -> {
			ProviderDescriptor desc = new ProviderDescriptor("openai", "OpenAI",
				"m", "embed", "ENV", true, (k, u, e) -> client);
			return new Prepared(desc, client, "m");
		};
		CliRunner runner = new CliRunner(this.out, factory, path -> "doc text", () -> null);
		int code = runner.run(new String[] { "rag", "q", "--doc", "doc.txt" });
		assertEquals(1, code);
		assertTrue(output().contains("不支持 Embedding"));
	}

	/** RAG：客户端是 EmbeddingClient 但描述符无默认嵌入模型 → 退出码 1。 */
	@Test
	public void ragWithoutDefaultEmbeddingModelReturnsUsage() {
		AiClient client = embeddingClient();
		ClientFactory factory = global -> {
			ProviderDescriptor desc = new ProviderDescriptor("openai", "OpenAI",
				"m", null, "ENV", true, (k, u, e) -> client);
			return new Prepared(desc, client, "m");
		};
		CliRunner runner = new CliRunner(this.out, factory, path -> "doc text", () -> null);
		int code = runner.run(new String[] { "rag", "q", "--doc", "doc.txt" });
		assertEquals(1, code);
		assertTrue(output().contains("无默认嵌入模型"));
	}

	/** REPL：空行跳过、quit 退出。 */
	@Test
	public void replSkipsBlankAndStopsOnQuit() {
		AiClient client = chatOnlyClient();
		List<String> lines = new ArrayList<>(List.of("", "  ", "quit"));
		ClientFactory factory = global -> new Prepared(null, client, "m");
		CliRunner runner = new CliRunner(this.out, factory, path -> "",
			() -> lines.isEmpty() ? null : lines.remove(0));
		int code = runner.run(new String[] { "repl" });
		assertEquals(0, code);
	}

	/** RAG 文件读取失败（默认 fileLoader）：退出码 1。 */
	@Test
	public void ragUnreadableFileReturnsUsage() {
		AiClient client = chatOnlyClient();
		ClientFactory factory = global -> new Prepared(null, client, "m");
		CliRunner runner = new CliRunner(this.out, factory);
		int code = runner.run(new String[] { "rag", "q", "--doc", "/no/such/file.txt" });
		assertEquals(1, code);
		assertTrue(output().contains("无法读取文档"));
	}

	/** DefaultClientFactory：显式 apiKey 优先；本地平台缺省用 dummy。 */
	@Test
	public void defaultFactoryApiKeyResolution() {
		Map<String, String> env = new HashMap<>();
		DefaultClientFactory factory = new DefaultClientFactory(env::get);

		// 显式 apiKey
		Prepared p = factory.prepare(new GlobalOptions("openai", "explicit-key", null, null));
		assertNotNull(p);
		assertEquals("gpt-4o-mini", p.model());

		// 本地平台 ollama 无需 key → dummy
		Prepared o = factory.prepare(new GlobalOptions("ollama", null, null, null));
		assertNotNull(o);
	}

	/** CliException 三参构造保留根因。 */
	@Test
	public void cliExceptionWithCause() {
		RuntimeException root = new IllegalStateException("root");
		CliException e = new CliException(CliException.EXIT_API, "boom", root);
		assertEquals(2, e.exitCode());
		assertEquals(root, e.getCause());
		assertEquals("boom", e.getMessage());
	}

	/** ArgsParser 私有构造器不可实例化。 */
	@Test
	public void argsParserPrivateCtor() throws Exception {
		Constructor<ArgsParser> ctor = ArgsParser.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("expected AssertionError");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof AssertionError);
		}
	}

	/** Command.parse(null) → null。 */
	@Test
	public void commandParseNull() {
		assertNull(Command.parse(null));
		assertEquals(Command.CHAT, Command.parse("chat"));
	}

	/** Environment.SYSTEM 委托 System.getenv。 */
	@Test
	public void systemEnvironmentReadsRealEnv() {
		String path = Environment.SYSTEM.getenv("PATH");
		assertNotNull(path);
	}
}
