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
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

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
 * CLI 补充覆盖：Main 私有构造器、DefaultClientFactory 模型透传、readLocalFile 异常路径、
 * RAG 的 --embedding-model 开关与空描述符分支。
 *
 * <p>零真实网络。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class CliCoverageExtra2Test {

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

	/** Main 私有构造器不可实例化。 */
	@Test
	public void mainPrivateCtorThrows() throws Exception {
		Constructor<Main> ctor = Main.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("expected AssertionError");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof AssertionError);
		}
	}

	/** DefaultClientFactory：显式 --model 透传覆盖平台默认模型。 */
	@Test
	public void factoryModelOverrideUsed() {
		DefaultClientFactory factory = new DefaultClientFactory(name -> null);
		Prepared p = factory.prepare(new GlobalOptions("openai", "k", "gpt-x", null));
		assertEquals("gpt-x", p.model());
	}

	/** readLocalFile 私有静态方法：不存在的路径抛 CliException（EXIT_USAGE）。 */
	@Test
	public void readLocalFileMissingPathThrows() throws Exception {
		Method m = CliRunner.class.getDeclaredMethod("readLocalFile", String.class);
		m.setAccessible(true);
		try {
			m.invoke(null, "/no/such/dir/file-xyz.txt");
			fail("expected CliException");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof CliException);
			CliException ce = (CliException) e.getCause();
			assertEquals(CliException.EXIT_USAGE, ce.exitCode());
			assertTrue(ce.getMessage().contains("无法读取文档"));
		}
	}

	/** readLocalFile 读取已存在文件：正常返回文本（覆盖 try 成功路径）。 */
	@Test
	public void readLocalFileExistingFileReturnsContent() throws Exception {
		java.nio.file.Path file = java.nio.file.Files.createTempFile("cli-doc", ".txt");
		java.nio.file.Files.writeString(file, "hello-doc", StandardCharsets.UTF_8);
		Method m = CliRunner.class.getDeclaredMethod("readLocalFile", String.class);
		m.setAccessible(true);
		Object result = m.invoke(null, file.toString());
		assertEquals("hello-doc", result);
		java.nio.file.Files.deleteIfExists(file);
	}

	/** REPL 默认行源：反射把静态 STDIN 替换为空流（EOF），readStdinLine 立即返回 null。 */
	@Test
	public void replDefaultLineSourceExitsOnEof() throws Exception {
		java.io.BufferedReader eof = new java.io.BufferedReader(
			new java.io.InputStreamReader(new java.io.ByteArrayInputStream(new byte[0]),
				StandardCharsets.UTF_8));
		replaceStdin(eof);

		AiClient client = new BothClient();
		ClientFactory factory = global -> new Prepared(null, client, "m");
		CliRunner runner = new CliRunner(this.out, factory);
		int code = runner.run(new String[] { "repl" });
		assertEquals(0, code);
		assertTrue(output().contains("sureai repl"));
	}

	/** readStdinLine：STDIN.readLine 抛 IOException → 返回 null（覆盖 catch 分支）。 */
	@Test
	public void readStdinLineIOExceptionReturnsNull() throws Exception {
		java.io.BufferedReader throwing = new java.io.BufferedReader(
			new java.io.StringReader("")) {
			@Override
			public String readLine() throws java.io.IOException {
				throw new java.io.IOException("stdin-boom");
			}
		};
		replaceStdin(throwing);
		java.lang.reflect.Method m = CliRunner.class
			.getDeclaredMethod("readStdinLine");
		m.setAccessible(true);
		Object result = m.invoke(null);
		assertEquals(null, result);
	}

	/** 通过 Unsafe 写入 static final STDIN 字段。 */
	private static void replaceStdin(java.io.BufferedReader reader) throws Exception {
		java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe")
			.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		Object unsafe = unsafeField.get(null);
		java.lang.reflect.Field stdinField = CliRunner.class.getDeclaredField("STDIN");
		stdinField.setAccessible(true);
		Object base = unsafe.getClass()
			.getMethod("staticFieldBase", java.lang.reflect.Field.class).invoke(unsafe, stdinField);
		long offset = (long) unsafe.getClass()
			.getMethod("staticFieldOffset", java.lang.reflect.Field.class).invoke(unsafe, stdinField);
		unsafe.getClass()
			.getMethod("putObject", Object.class, long.class, Object.class)
			.invoke(unsafe, base, offset, reader);
	}

	/** RAG：显式 --embedding-model 跳过默认模型推导。 */
	@Test
	public void ragWithExplicitEmbeddingModel() {
		AiClient client = new BothClient();
		ProviderDescriptor desc = new ProviderDescriptor("openai", "OpenAI",
			"m", null, "ENV", true, (k, u, e) -> client);
		ClientFactory factory = global -> new Prepared(desc, client, "m");
		CliRunner runner = new CliRunner(this.out, factory, path -> "doc text", () -> null);
		int code = runner.run(new String[] { "rag", "q", "--doc", "d.txt",
			"--embedding-model", "embed-x" });
		assertEquals(0, code);
	}

	/** RAG：描述符为 null 且未指定嵌入模型 → 退出码 1（覆盖空描述符分支）。 */
	@Test
	public void ragNullDescriptorNoEmbeddingModel() {
		AiClient client = new BothClient();
		ClientFactory factory = global -> new Prepared(null, client, "m");
		CliRunner runner = new CliRunner(this.out, factory, path -> "doc text", () -> null);
		int code = runner.run(new String[] { "rag", "q", "--doc", "d.txt" });
		assertEquals(1, code);
		assertTrue(output().contains("无默认嵌入模型"));
	}

	/** 同时实现对话与向量的 fake 客户端。 */
	static final class BothClient implements AiClient, EmbeddingClient {
		@Override
		public String name() {
			return "both";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			return CliRunnerTestSupport.fixedReply("ans");
		}

		@Override
		public void chatStream(ChatRequest request, java.util.function.Consumer<ChatStreamChunk> c) {
			// 不使用
		}

		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			return EmbeddingResponse.of(request.model(),
				java.util.List.of(new float[] { 0.1f, 0.2f, 0.3f }), null);
		}

		@Override
		public void close() {
		}
	}
}
