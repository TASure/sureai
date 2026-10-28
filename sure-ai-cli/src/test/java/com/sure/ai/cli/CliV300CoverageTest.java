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
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;

/**
 * v3.0.0 覆盖分支：Main 入口 / default keySet / chat 滚动窗口 / new 失败分支 /
 * doctor 版本变体 / probeMaven 超时与异常 / detectLocalArtifact 边界 / mask/quote 边界。
 *
 * @author sureai
 * @since 3.0.0
 */
public class CliV300CoverageTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private ByteArrayOutputStream buffer;
	private PrintStream out;

	@Before
	public void setUp() {
		this.buffer = new ByteArrayOutputStream();
		this.out = new PrintStream(buffer, false, StandardCharsets.UTF_8);
	}

	private static Environment mapEnv(Map<String, String> vars) {
		return new Environment() {
			@Override
			public String getenv(String name) {
				return vars.get(name);
			}

			@Override
			public java.util.Set<String> keySet() {
				return vars.keySet();
			}
		};
	}

	/** 固定回复客户端（AiClient 实现，零网络）。 */
	private static final class CountingClient implements AiClient {
		final String reply;

		CountingClient(String reply) {
			this.reply = reply;
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
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

	@Test
	public void mainRunCliReturnsExitCode() {
		// 经 runCli 入口（不触发 System.exit）；Main 直写 System.out，临时重定向
		java.io.PrintStream saved = System.out;
		try {
			System.setOut(out);
			int code = Main.runCli(new String[] {"version"});
			assertEquals(0, code);
			out.flush();
			assertTrue(buffer.toString().contains("sureai CLI"));
		} finally {
			System.setOut(saved);
		}
	}

	@Test
	public void configOverviewUsesDefaultKeySet() {
		// Environment.SYSTEM 走 default keySet()（真实 System.getenv().keySet()）
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			Environment.SYSTEM, path -> "", () -> null);
		int code = runner.run(new String[] {"config"});
		assertEquals(0, code);
		assertTrue(buffer.toString().contains("sureai config"));
	}

	@Test
	public void chatScrollsBeyondHistoryLimit() {
		Deque<String> lines = new ArrayDeque<>();
		for (int i = 0; i < 45; i++) {
			lines.add("第 " + i + " 轮问题");
		}
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("答案"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> lines.poll());
		int code = runner.run(new String[] {"chat", "开场问题"});
		assertEquals(0, code);
		assertTrue(lines.isEmpty());
	}

	@Test
	public void newWithRootDirNameFallsBackAndFails() throws Exception {
		// /proc 为 Linux 专属文件系统，Windows/macOS 无此语义，跳过
		assumeTrue("仅 Linux 具备 /proc 只读语义，Windows/macOS 跳过",
				Files.isDirectory(Path.of("/proc")));
		// --name "/" → Path.of("/").getFileName()==null → 兜底目录名；
		// 防覆盖检查 Files.walk("/") 遇只读目录（/proc 等）抛 IOException → EXIT_API
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		int code = runner.run(new String[] {"new", "rag-kb", "--name", "/"});
		assertEquals(CliException.EXIT_API, code);
		// Files.walk 内部以 UncheckedIOException 包装 → run 外层 [API 异常]
		assertTrue(buffer.toString().contains("API 异常"));
	}

	@Test
	public void newToNonEmptyDirRejected() throws Exception {
		// 目标目录已存在且非空 → 防覆盖 IllegalArgumentException → EXIT_USAGE
		Path dir = tmp.newFolder("non-empty").toPath();
		Files.writeString(dir.resolve("stale.txt"), "x");
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		int code = runner.run(new String[] {"new", "rag-kb", "--name", dir.toString()});
		assertEquals(CliException.EXIT_USAGE, code);
		assertTrue(buffer.toString().contains("模板生成失败"));
	}

	@Test
	public void newToReadOnlyPathThrowsIo() {
		// /proc 为 Linux 专属文件系统，Windows/macOS 无此语义，跳过
		assumeTrue("仅 Linux 具备 /proc 只读语义，Windows/macOS 跳过",
				Files.isDirectory(Path.of("/proc")));
		// /proc 只读 → createDirectories 抛 IOException → EXIT_API
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		int code = runner.run(new String[] {"new", "rag-kb", "--name", "/proc/sureai-no-write"});
		assertEquals(CliException.EXIT_API, code);
	}

	@Test
	public void doctorHandlesJdkVariants() {
		String saved = System.getProperty("java.version");
		try {
			// contains("25.0") 分支
			System.setProperty("java.version", "1.25.0");
			CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
				mapEnv(new HashMap<>()), path -> "", () -> null);
			int code = runner.run(new String[] {"doctor"});
			assertEquals(0, code);
			assertTrue(buffer.toString().contains("满足 25 基线"));
			// contains("25-ea") 分支
			buffer.reset();
			System.setProperty("java.version", "jdk25-ea");
			code = runner.run(new String[] {"doctor"});
			assertEquals(0, code);
			assertTrue(buffer.toString().contains("满足 25 基线"));
			// 非 25（false 分支 → 建议 JDK 25）
			buffer.reset();
			System.setProperty("java.version", "21.0.1");
			code = runner.run(new String[] {"doctor"});
			assertEquals(0, code);
			assertTrue(buffer.toString().contains("建议 JDK 25"));
		} finally {
			if (saved == null) {
				System.clearProperty("java.version");
			} else {
				System.setProperty("java.version", saved);
			}
		}
	}

	@Test
	public void doctorReportsMissingLocalArtifact() {
		String savedHome = System.getProperty("user.home");
		try {
			// user.home 指向无 .m2 的临时目录 → detectLocalArtifact 返回 null → 「未安装」
			System.setProperty("user.home", tmp.getRoot().toString());
			CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
				mapEnv(new HashMap<>()), path -> "", () -> null);
			int code = runner.run(new String[] {"doctor"});
			assertEquals(0, code);
			assertTrue(buffer.toString().contains("未安装（.m2 缺失）"));
		} finally {
			if (savedHome == null) {
				System.clearProperty("user.home");
			} else {
				System.setProperty("user.home", savedHome);
			}
		}
	}

	@Test
	public void probeMavenHandlesMissingCommand() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		String result = runner.probeMaven(new ProcessBuilder("sureai-no-such-cmd-xyz", "-v"));
		assertTrue(result.contains("未找到"));
	}

	@Test
	public void probeMavenHandlesInterrupt() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		Thread.currentThread().interrupt();
		try {
			String result = runner.probeMaven(new ProcessBuilder("sleep", "30"));
			assertTrue(result.contains("未找到"));
		} finally {
			Thread.interrupted(); // 清中断标志
		}
	}

	@Test
	public void probeMavenHandlesTimeout() {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		String result = runner.probeMaven(new ProcessBuilder("sleep", "30"));
		assertTrue(result.contains("超时"));
	}

	@Test
	public void detectLocalArtifactMissingDirReturnsNull() throws Exception {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		assertNull(runner.detectLocalArtifact(
			tmp.newFile("not-a-dir").toPath()));
	}

	@Test
	public void detectLocalArtifactEmptyDirReturnsNull() throws Exception {
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		assertNull(runner.detectLocalArtifact(tmp.newFolder("empty").toPath()));
	}

	@Test
	public void detectLocalArtifactSortsVersionSegments() throws Exception {
		Path dir = tmp.newFolder("versions").toPath();
		Files.createDirectories(dir.resolve("1.0"));
		Files.createDirectories(dir.resolve("1.0.0-SNAPSHOT"));
		Files.createDirectories(dir.resolve("2.10.0-SNAPSHOT"));
		Files.createDirectories(dir.resolve("2.10.0-RC1"));
		Files.createDirectories(dir.resolve("2.9.0-SNAPSHOT"));
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(new HashMap<>()), path -> "", () -> null);
		String latest = runner.detectLocalArtifact(dir);
		// 版本段感知：2.10.0-SNAPSHOT > 2.9.0-SNAPSHOT > 1.0.0-SNAPSHOT > 1.0（长度比较）
		assertNotNull(latest);
		assertEquals("2.10.0-SNAPSHOT", latest);
	}

	@Test
	public void configMaskAndQuoteBranches() {
		Map<String, String> vars = new HashMap<>();
		vars.put("SURE_AI_SHORT", "ab");                 // mask 短值 → ****
		vars.put("SURE_AI_UNSET", null);                 // mask null → (未设置)
		vars.put("SURE_AI_OPENAI_API_KEY", "sk ab cd");  // 平台 key 值含空格 → quote 引号分支
		vars.put("SURE_AI_DEEPSEEK_API_KEY", "sk-plain"); // 无空格 → quote 原样分支
		CliRunner runner = new CliRunner(out, factoryFor(new CountingClient("x"), "m"),
			mapEnv(vars), path -> "", () -> null);
		// 概览分支：mask 短值 / null
		int code = runner.run(new String[] {"config"});
		assertEquals(0, code);
		String text = buffer.toString();
		if (!text.contains("SURE_AI_SHORT=****") || !text.contains("SURE_AI_UNSET=(未设置)")) {
			System.err.println("### CONFIG OUT: " + text);
		}
		assertTrue(text.contains("SURE_AI_SHORT=****"));
		assertTrue(text.contains("SURE_AI_UNSET=(未设置)"));
		// export 分支：仅导出平台 key，值含空格加引号
		buffer.reset();
		code = runner.run(new String[] {"config", "--export"});
		assertEquals(0, code);
		text = buffer.toString();
		assertTrue(text.contains("export SURE_AI_OPENAI_API_KEY='sk ab cd'"));
		assertTrue(text.contains("export SURE_AI_DEEPSEEK_API_KEY=sk-plain"));
	}
}
