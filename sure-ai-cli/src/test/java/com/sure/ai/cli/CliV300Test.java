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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;

/**
 * v3.0.0 新命令测试：多轮 chat / new / config / doctor / version（零真实网络）。
 *
 * @author sureai
 * @since 3.0.0
 */
public class CliV300Test {

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

	/** 内存环境变量源。 */
	private static Environment mapEnv(Map<String, String> vars) {
		return new Environment() {
			@Override
			public String getenv(String name) {
				return vars.get(name);
			}

			@Override
			public Set<String> keySet() {
				return vars.keySet();
			}
		};
	}

	/** 固定回复客户端：记录每次请求的消息数。 */
	private static final class CountingClient implements AiClient {
		final Deque<Integer> messageCounts = new ArrayDeque<>();
		final String reply;

		CountingClient(String reply) {
			this.reply = reply;
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			messageCounts.add(request.messages().size());
			ChatMessage msg = ChatMessage.assistant(reply);
			return ChatResponse.of("id", "m", List.of(Choice.of(0, msg, "stop")), null, "{}");
		}

		@Override
		public ChatResponse chat(String model, String prompt) {
			return chat(ChatRequest.builder().model(model)
				.messages(List.of(ChatMessage.user(prompt))).build());
		}

		@Override
		public void chatStream(ChatRequest request,
				java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
			// 无流式实现
		}

		@Override
		public void close() {
			// 无资源，无需关闭
		}

		@Override
		public String name() {
			return "fake";
		}
	}

	/** 固定回复客户端 + 工厂。 */
	private static ClientFactory factoryFor(AiClient client, String defaultModel) {
		ProviderDescriptor desc = new ProviderDescriptor("openai", "OpenAI",
			defaultModel, "text-embed", "SURE_AI_OPENAI_API_KEY", true,
			(k, u, e) -> client);
		return global -> new Prepared(desc, client,
			(global.model() == null || global.model().isBlank()) ? defaultModel : global.model());
	}

	// ---------- 多轮 chat ----------

	@Test
	public void chatMultiTurnBuildsHistoryUntilExit() {
		CountingClient client = new CountingClient("答");
		Deque<String> lines = new ArrayDeque<>(List.of("第二问", "第三问", "exit"));
		CliRunner runner = new CliRunner(out, factoryFor(client, "m"),
			Environment.SYSTEM, path -> "", () -> lines.isEmpty() ? null : lines.poll());
		int code = runner.run(new String[] {"--provider", "openai", "chat", "第一问"});
		assertEquals(0, code);
		String outText = output();
		assertTrue(outText.contains("答"));
		assertTrue("历史应逐轮增长：1 → 3 → 5", client.messageCounts.contains(1)
			&& client.messageCounts.contains(3) && client.messageCounts.contains(5));
	}

	@Test
	public void chatOnceStaysSingleTurn() {
		CountingClient client = new CountingClient("答");
		CliRunner runner = new CliRunner(out, factoryFor(client, "m"));
		int code = runner.run(new String[] {"chat", "--once", "只问一次"});
		assertEquals(0, code);
		assertEquals("--once 仅一轮请求", 1, client.messageCounts.size());
		assertTrue(output().contains("答"));
	}

	@Test
	public void chatStopsOnEofOrEmpty() {
		CountingClient client = new CountingClient("答");
		CliRunner runner = new CliRunner(out, factoryFor(client, "m"),
			Environment.SYSTEM, path -> "", () -> null);
		int code = runner.run(new String[] {"chat", "问题"});
		assertEquals(0, code);
		assertEquals("EOF 退出，仅一轮", 1, client.messageCounts.size());
	}

	// ---------- new ----------

	@Test
	public void newListShowsTemplates() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"));
		int code = runner.run(new String[] {"new", "--list"});
		assertEquals(0, code);
		String outText = output();
		assertTrue(outText.contains("rag-kb"));
		assertTrue(outText.contains("customer-service"));
		assertTrue(outText.contains("code-assistant"));
	}

	@Test
	public void newGeneratesProjectWithPlaceholders() throws IOException {
		Path target = Files.createTempDirectory("sureai-new-test");
		Path proj = target.resolve("demo-kb");
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"));
		int code = runner.run(new String[] {
			"new", "rag-kb", "--name", proj.toString(),
			"--group", "com.acme", "--artifact", "kb-app"});
		if (code != 0) {
			System.err.println("### CLI OUTPUT: " + buffer.toString());
		}
		assertEquals(0, code);
		assertTrue("pom 已生成", Files.isRegularFile(proj.resolve("pom.xml")));
		assertTrue("主类已生成且包路径替换正确",
			Files.isRegularFile(proj.resolve("src/main/java/com/acme/demokb/Main.java")));
		assertTrue("README 已生成", Files.isRegularFile(proj.resolve("README.md")));
		String pom = Files.readString(proj.resolve("pom.xml"), StandardCharsets.UTF_8);
		assertTrue("artifactId 占位符已替换", pom.contains("<artifactId>kb-app</artifactId>"));
	}

	@Test
	public void newUnknownTemplateRejected() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"));
		int code = runner.run(new String[] {"new", "nope"});
		assertEquals(1, code);
		assertTrue(output().contains("失败"));
	}

	// ---------- config ----------

	@Test
	public void configShowsMaskedVariables() {
		Map<String, String> vars = new HashMap<>();
		vars.put("SURE_AI_OPENAI_API_KEY", "sk-abcdef123456");
		vars.put("PATH", "/usr/bin");
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(vars), path -> "", () -> null);
		int code = runner.run(new String[] {"config"});
		assertEquals(0, code);
		String outText = output();
		assertTrue(outText.contains("SURE_AI_OPENAI_API_KEY=sk-a****"));
		assertTrue("非 SURE_AI_ 变量不显示", !outText.contains("PATH="));
		assertTrue("ollama/llamacpp 免 key 计为就绪", outText.contains("3/23"));
	}

	@Test
	public void configCheckFailsWhenKeysMissing() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		int code = runner.run(new String[] {"config", "--check"});
		assertEquals("缺失 key 返回 1", 1, code);
		assertTrue(output().contains("缺少 API Key"));
	}

	@Test
	public void configCheckPassesWhenKeysPresent() {
		Map<String, String> vars = new HashMap<>();
		for (ProviderDescriptor d : ProviderRegistry.all()) {
			if (d.envApiKey() != null) {
				vars.put(d.envApiKey(), "set-" + d.name());
			}
		}
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(vars), path -> "", () -> null);
		int code = runner.run(new String[] {"config", "--check"});
		assertEquals(0, code);
		assertTrue(output().contains("全部平台 API Key 就绪"));
	}

	@Test
	public void configExportWritesExportLines() {
		Map<String, String> vars = new HashMap<>();
		vars.put("SURE_AI_OPENAI_API_KEY", "sk-abc");
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(vars), path -> "", () -> null);
		int code = runner.run(new String[] {"config", "--export"});
		assertEquals(0, code);
		String outText = output();
		assertTrue(outText.contains("export SURE_AI_OPENAI_API_KEY=sk-abc"));
		assertEquals("仅输出一行 export", outText.indexOf("export SURE_AI_OPENAI_API_KEY=sk-abc"),
			outText.lastIndexOf("export SURE_AI_OPENAI_API_KEY=sk-abc"));
	}

	// ---------- doctor / version ----------

	@Test
	public void doctorReportsEnvironment() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		int code = runner.run(new String[] {"doctor"});
		assertEquals(0, code);
		String outText = output();
		assertTrue(outText.contains("JDK 版本："));
		assertTrue(outText.contains("Maven："));
		assertTrue(outText.contains("sure-ai-all 制品"));
		assertTrue("空环境仅免 key 平台就绪", outText.contains("平台密钥就绪：2/23"));
	}

	@Test
	public void versionPrintsCliVersion() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"));
		int code = runner.run(new String[] {"version"});
		assertEquals(0, code);
		assertTrue(output().contains("sureai CLI " + CliRunner.VERSION));
	}

	// ---------- 参数解析 ----------

	@Test
	public void parserAcceptsNewAndConfigFlags() throws Exception {
		ParsedCommand nc = ArgsParser.parse(new String[] {"new", "rag-kb", "--name", "kb", "--group", "g"});
		assertEquals(Command.NEW, nc.command());
		assertEquals("kb", nc.flag("name"));
		assertEquals("g", nc.flag("group"));
		assertEquals("rag-kb", nc.positional().get(0));

		ParsedCommand cc = ArgsParser.parse(new String[] {"config", "--check"});
		assertEquals(Command.CONFIG, cc.command());
		assertEquals("true", cc.flag("check"));

		ParsedCommand vc = ArgsParser.parse(new String[] {"version"});
		assertEquals(Command.VERSION, vc.command());
	}

	@Test
	public void parserRejectsUnknownFlagsPerCommand() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"));
		int code = runner.run(new String[] {"new", "rag-kb", "--doc", "x"});
		assertEquals(1, code);
		assertTrue(output().contains("未知选项: --doc"));
	}
}
