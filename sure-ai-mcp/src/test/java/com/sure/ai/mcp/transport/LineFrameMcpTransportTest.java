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
package com.sure.ai.mcp.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;

/**
 * {@link LineFrameMcpTransport} 行帧传输测试：用 Piped 流模拟对端，零真实进程/网络。
 *
 * @author sureai
 * @since 1.2.0
 */
public class LineFrameMcpTransportTest {

	/** 可注入流的测试传输。 */
	private static final class TestTransport extends LineFrameMcpTransport {
		TestTransport(PipedInputStream clientRead, PipedOutputStream clientWrite, Duration timeout) {
			super(clientRead, clientWrite, timeout);
		}

		TestTransport(PipedInputStream clientRead, PipedOutputStream clientWrite, Duration timeout, int maxLineBytes) {
			super(clientRead, clientWrite, timeout, maxLineBytes);
		}

		@Override
		protected void doClose() {
		}
	}

	/** 请求-响应按 id 配对。 */
	@Test
	public void requestResponseMatchedById() throws Exception {
		PipedInputStream serverRead = new PipedInputStream();
		PipedOutputStream serverWrite = new PipedOutputStream();
		PipedInputStream clientRead = new PipedInputStream(serverWrite);
		PipedOutputStream clientWrite = new PipedOutputStream(serverRead);

		// 对端线程：读一行，回一行带 id 的响应
		Thread fakeServer = new Thread(() -> {
			try {
				java.io.BufferedReader br = new java.io.BufferedReader(
					new java.io.InputStreamReader(serverRead, StandardCharsets.UTF_8));
				String line = br.readLine();
				JsonObject req = Json.parse(line).getAsJsonObject();
				long id = req.optLong("id", 0L);
				String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"echo\":\""
					+ req.getString("method") + "\"}}";
				serverWrite.write((resp + "\n").getBytes(StandardCharsets.UTF_8));
				serverWrite.flush();
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
		fakeServer.setDaemon(true);
		fakeServer.start();

		TestTransport t = new TestTransport(clientRead, clientWrite, Duration.ofSeconds(5));
		assertTrue(t.isOpen());
		McpResponse r = t.sendRequest(new McpRequest(1L, "tools/list", null));
		assertEquals(Long.valueOf(1), r.id());
		assertEquals("tools/list", r.result().getAsJsonObject().getString("echo"));
		t.close();
		assertFalse(t.isOpen());
	}

	/** 读取非法 JSON 行不崩溃，后续正常响应仍能配对。 */
	@Test
	public void malformedLineIgnored() throws Exception {
		PipedInputStream serverRead = new PipedInputStream();
		PipedOutputStream serverWrite = new PipedOutputStream();
		PipedInputStream clientRead = new PipedInputStream(serverWrite);
		PipedOutputStream clientWrite = new PipedOutputStream(serverRead);

		TestTransport t = new TestTransport(clientRead, clientWrite, Duration.ofSeconds(5));
		// 对端先写垃圾行，再写真实响应
		serverWrite.write("not json\n".getBytes(StandardCharsets.UTF_8));
		serverWrite.write("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"v\":1}}\n".getBytes(StandardCharsets.UTF_8));
		serverWrite.flush();

		McpResponse r = t.sendRequest(new McpRequest(9L, "ping", null));
		assertEquals(1, r.result().getAsJsonObject().getInt("v"));
		t.close();
	}

	/** P1-5 测试 1：对端永不回复，超时后抛 AiException。 */
	@Test
	public void readTimeoutThrowsAiException() throws Exception {
		PipedInputStream serverRead = new PipedInputStream();
		PipedOutputStream serverWrite = new PipedOutputStream();
		PipedInputStream clientRead = new PipedInputStream(serverWrite);
		PipedOutputStream clientWrite = new PipedOutputStream(serverRead);

		TestTransport t = new TestTransport(clientRead, clientWrite, Duration.ofSeconds(1));
		try {
			t.sendRequest(new McpRequest(100L, "never/replies", null));
			fail("应抛出超时 AiException");
		} catch (AiException ex) {
			assertTrue("异常消息应包含'超时': " + ex.getMessage(), ex.getMessage().contains("超时"));
		} finally {
			t.close();
		}
	}

	/** P1-5 测试 2：对端异常断开（EOF），pending 被异常完成，传输关闭。 */
	@Test
	public void eofDisconnectFailsPendingAndCloses() throws Exception {
		PipedInputStream serverRead = new PipedInputStream();
		PipedOutputStream serverWrite = new PipedOutputStream();
		PipedInputStream clientRead = new PipedInputStream(serverWrite);
		PipedOutputStream clientWrite = new PipedOutputStream(serverRead);

		TestTransport t = new TestTransport(clientRead, clientWrite, Duration.ofSeconds(5));

		AtomicReference<Throwable> err = new AtomicReference<>();
		Thread caller = new Thread(() -> {
			try {
				t.sendRequest(new McpRequest(200L, "wait/close", null));
			} catch (Throwable ex) {
				err.set(ex);
			}
		});
		caller.start();

		// 等待请求写入对端，然后关闭对端输出流 → 客户端读到 EOF
		Thread.sleep(200);
		serverWrite.close();

		caller.join(5000);
		assertFalse("caller 线程应已结束", caller.isAlive());

		assertTrue("应抛 AiException", err.get() instanceof AiException);
		// sendRequest 会把 future 异常包装为 "MCP 请求发送失败: <method>"，真实原因在 cause 链中
		String causeChain = collectMessages(err.get());
		assertTrue("异常原因链应包含'关闭'或'读取失败': " + causeChain,
			causeChain.contains("关闭") || causeChain.contains("读取失败"));
		assertFalse("传输应已关闭", t.isOpen());
	}

	/** 递归收集异常链所有消息，便于断言根因。 */
	private static String collectMessages(Throwable t) {
		StringBuilder sb = new StringBuilder();
		Throwable cur = t;
		while (cur != null) {
			if (cur.getMessage() != null) {
				sb.append(cur.getMessage()).append(" | ");
			}
			cur = cur.getCause();
		}
		return sb.toString();
	}

	/** P1-5 测试 3：单行超过 maxLineBytes 上限，传输被关闭，pending 异常完成。 */
	@Test
	public void oversizedFrameClosesTransport() throws Exception {
		PipedInputStream serverRead = new PipedInputStream();
		PipedOutputStream serverWrite = new PipedOutputStream();
		PipedInputStream clientRead = new PipedInputStream(serverWrite);
		PipedOutputStream clientWrite = new PipedOutputStream(serverRead);

		// 用 256 字节上限（远小于默认 64MB），写 512 字节无换行
		int maxLine = 256;
		TestTransport t = new TestTransport(clientRead, clientWrite, Duration.ofSeconds(5), maxLine);

		AtomicReference<Throwable> err = new AtomicReference<>();
		Thread caller = new Thread(() -> {
			try {
				t.sendRequest(new McpRequest(300L, "bogus/frame", null));
			} catch (Throwable ex) {
				err.set(ex);
			}
		});
		caller.start();

		// 等待请求写入，然后对端写入 512 字节 'a'（无 \n），触发超限
		Thread.sleep(200);
		byte[] big = new byte[512];
		java.util.Arrays.fill(big, (byte) 'a');
		serverWrite.write(big);
		serverWrite.flush();

		caller.join(5000);
		assertFalse("caller 线程应已结束", caller.isAlive());
		assertFalse("超限后传输应关闭", t.isOpen());
		assertTrue("pending 请求应异常完成", err.get() instanceof AiException);
		t.close();
	}
}
