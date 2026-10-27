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

package com.sure.ai.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import org.junit.Test;

/**
 * MCP 帧解析模糊健壮性测试（sureai v2.1.0「生产级信任」批次 1）。
 *
 * <p>零真实网络、零新依赖。本工程无 {@code LineFrameMcpTransport}，实际帧解析为 stdio NDJSON 行帧：
 * 长度闸门 {@link StdioMcpServerTransport.LineLimitedInputStream} + 内容分发 {@link McpServer#dispatch(String)}。
 * 方法：固定种子 {@code Random(42)} 随机字节/行，叠加边界枚举（超长帧、截断帧、非法 UTF-8、控制字符）。
 * 断言原则：长度闸门只抛 {@link IOException}（超限即拒）；dispatch 必须自身兜住一切、绝不逃逸。</p>
 *
 * @author sureai
 * @since 2.1.0
 */
public class McpFrameFuzzTest {

	/** 固定随机种子。 */
	private static final long SEED = 42L;

	/**
	 * 长度闸门随机 fuzz：固定种子随机字节流过限长输入流，只允许 IOException，不得 JVM 崩溃。
	 *
	 * @throws Exception 读取异常
	 */
	@Test
	public void fuzzLineLimitRandom() throws Exception {
		Random rnd = new Random(SEED);
		for (int round = 0; round < 300; round++) {
			int len = 1 + rnd.nextInt(512);
			byte[] data = new byte[len];
			rnd.nextBytes(data);
			readAllLenient(new StdioMcpServerTransport.LineLimitedInputStream(
				new ByteArrayInputStream(data), 64 * 1024 * 1024));
		}
	}

	/**
	 * 长度边界：单字节逐读与批量读两种路径都要在超限时抛 IOException、遇 {@code \n} 复位计数。
	 *
	 * @throws Exception 读取异常
	 */
	@Test
	public void lineLimitBoundary() throws Exception {
		// 恰好达到上限：不抛
		byte[] atLimit = new byte[100];
		java.util.Arrays.fill(atLimit, (byte) 'a');
		try (InputStream in = new StdioMcpServerTransport.LineLimitedInputStream(
			new ByteArrayInputStream(atLimit), 100)) {
			assertEquals(100, readCount(in));
		}
		// 超过上限 1 字节：逐读路径抛 IOException
		byte[] overLimit = new byte[101];
		java.util.Arrays.fill(overLimit, (byte) 'a');
		try (InputStream in = new StdioMcpServerTransport.LineLimitedInputStream(
			new ByteArrayInputStream(overLimit), 100)) {
			readCount(in);
			fail("应因超限抛 IOException");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("exceeds"));
		}
		// \n 复位：两段各 60 字节（上限 100）应都不抛
		byte[] reset = new byte[121];
		java.util.Arrays.fill(reset, 0, 60, (byte) 'a');
		reset[60] = '\n';
		java.util.Arrays.fill(reset, 61, 121, (byte) 'b');
		try (InputStream in = new StdioMcpServerTransport.LineLimitedInputStream(
			new ByteArrayInputStream(reset), 100)) {
			assertEquals(121, readCount(in));
		}
		// 批量读路径超限
		byte[] overBatch = new byte[250];
		java.util.Arrays.fill(overBatch, (byte) 'x');
		try (InputStream in = new StdioMcpServerTransport.LineLimitedInputStream(
			new ByteArrayInputStream(overBatch), 100)) {
			byte[] buf = new byte[64];
			// 连续批量读直到抛
			while (true) {
				int n = in.read(buf);
				if (n == -1) {
					break;
				}
			}
			fail("批量读路径应因超限抛 IOException");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("exceeds"));
		}
	}

	/**
	 * 截断/畸形 JSON-RPC 内容：dispatch 必须全程自兜（parse error / invalid request / internal error 帧），
	 * 绝不向传输层抛 JVM 级异常。
	 */
	@Test
	public void fuzzDispatchTruncatedFrames() {
		McpServer server = new McpServer().serverInfo("t", "1").instructions("x");
		Random rnd = new Random(SEED);
		String[] seeds = {
			"{", "[", "\"", "{\"jsonrpc\":\"2.0\",\"method\":", "{\"id\":1,\"method\":\"tools/call\",",
			"{\"id\":null}", "not json at all", "{\"method\":\"initialize\",\"params\":",
			"{\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":null", ""
		};
		for (String s : seeds) {
			assertDispatchStable(server, s);
		}
		// 随机 JSON-ish 帧
		for (int round = 0; round < 500; round++) {
			int len = 1 + rnd.nextInt(128);
			StringBuilder sb = new StringBuilder(len);
			String[] alphabet = { "{", "}", "[", "]", "\"", ":", ",", "n", "u", "l", "l", "1", " ", "\n", "\\" };
			for (int i = 0; i < len; i++) {
				sb.append(alphabet[rnd.nextInt(alphabet.length)]);
			}
			assertDispatchStable(server, sb.toString());
		}
		// 1MB 超长帧
		StringBuilder big = new StringBuilder(1024 * 1024);
		big.append("{\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\"");
		for (int i = 0; i < 1024 * 1024 - 40; i++) {
			big.append('z');
		}
		big.append("\"}}");
		assertDispatchStable(server, big.toString());
	}

	/** dispatch 必须正常返回（String 或 null），不得抛任何 Throwable。 */
	private static void assertDispatchStable(McpServer server, String line) {
		try {
			String resp = server.dispatch(line);
			// 返回值非契约约束，仅记录；可为 null（通知）
			assertTrue(resp == null || resp.startsWith("{"));
		} catch (StackOverflowError | OutOfMemoryError | NullPointerException
				| ArrayIndexOutOfBoundsException | NumberFormatException e) {
			fail("McpServer.dispatch 触发 JVM 级崩溃 " + e.getClass().getName()
				+ "，输入前 100 字符: " + (line.length() <= 100 ? line : line.substring(0, 100)));
		}
	}

	/** 读完全部字节并返回字节数（吞掉超限 IOException 之外的 EOF）。 */
	private static int readCount(InputStream in) throws IOException {
		int total = 0;
		int b;
		while ((b = in.read()) != -1) {
			total++;
		}
		return total;
	}

	/** 读完全部；除 IOException 外不得 JVM 崩溃。 */
	private static void readAllLenient(InputStream in) {
		byte[] buf = new byte[64];
		try {
			while (true) {
				int n = in.read(buf);
				if (n == -1) {
					break;
				}
			}
		} catch (IOException expected) {
			// 超限：允许
		} catch (StackOverflowError | OutOfMemoryError | NullPointerException e) {
			fail("LineLimitedInputStream 触发 JVM 级崩溃 " + e.getClass().getName());
		}
	}
}
