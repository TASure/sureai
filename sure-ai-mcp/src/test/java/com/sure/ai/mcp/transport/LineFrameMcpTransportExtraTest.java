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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;

/**
 * {@link LineFrameMcpTransport} 补测：覆盖 null 超时、通知写失败、空行/非对象行/服务端主动帧
 * 的分发分支、关闭后拒绝调用，以及 {@code LineLimitedInputStream} 的直接读写。
 *
 * @author sureai
 * @since 2.6.0
 */
public class LineFrameMcpTransportExtraTest {

	/** 可注入流的测试传输。 */
	private static final class TestTransport extends LineFrameMcpTransport {
		TestTransport(PipedInputStream in, PipedOutputStream out, Duration timeout) {
			super(in, out, timeout);
		}

		@Override
		protected void doClose() {
		}
	}

	/** 构造一对管道。 */
	private static TestPair pair() throws Exception {
		PipedInputStream serverRead = new PipedInputStream();
		PipedOutputStream serverWrite = new PipedOutputStream();
		PipedInputStream clientRead = new PipedInputStream(serverWrite);
		PipedOutputStream clientWrite = new PipedOutputStream(serverRead);
		TestPair p = new TestPair();
		p.serverRead = serverRead;
		p.serverWrite = serverWrite;
		p.clientRead = clientRead;
		p.clientWrite = clientWrite;
		return p;
	}

	/** 管道对。 */
	private static final class TestPair {
		private PipedInputStream serverRead;
		private PipedOutputStream serverWrite;
		private PipedInputStream clientRead;
		private PipedOutputStream clientWrite;
	}

	/** null 超时使用默认超时。 */
	@Test
	public void nullTimeoutFallsBack() throws Exception {
		TestPair p = pair();
		TestTransport t = new TestTransport(p.clientRead, p.clientWrite, null);
		assertTrue(t.isOpen());
		t.close();
	}

	/** 启动后台 fake server：先写 ignored 行，再读客户端请求后回匹配响应。 */
	private static void startFakeServer(TestPair p, String ignoredLine, long expectId, String resultBody) {
		Thread s = new Thread(() -> {
			try {
				if (ignoredLine != null) {
					p.serverWrite.write((ignoredLine + "\n").getBytes(StandardCharsets.UTF_8));
					p.serverWrite.flush();
				}
				java.io.BufferedReader br = new java.io.BufferedReader(
					new java.io.InputStreamReader(p.serverRead, StandardCharsets.UTF_8));
				String req = br.readLine();
				JsonObject o = Json.parse(req).getAsJsonObject();
				long id = o.optLong("id", expectId);
				String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultBody + "}";
				p.serverWrite.write((resp + "\n").getBytes(StandardCharsets.UTF_8));
				p.serverWrite.flush();
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
		s.setDaemon(true);
		s.start();
	}

	/** 对端先写空行再写真实响应：空行被跳过。 */
	@Test
	public void emptyLineSkipped() throws Exception {
		TestPair p = pair();
		startFakeServer(p, "", 5L, "{\"v\":1}");
		TestTransport t = new TestTransport(p.clientRead, p.clientWrite, Duration.ofSeconds(5));
		McpResponse r = t.sendRequest(new McpRequest(5L, "ping", null));
		assertEquals(1, r.result().getAsJsonObject().getInt("v"));
		t.close();
	}

	/** 对端写 JSON 数组（非对象）行：被忽略，不崩溃。 */
	@Test
	public void nonObjectLineIgnored() throws Exception {
		TestPair p = pair();
		startFakeServer(p, "[1,2,3]", 6L, "{\"v\":2}");
		TestTransport t = new TestTransport(p.clientRead, p.clientWrite, Duration.ofSeconds(5));
		McpResponse r = t.sendRequest(new McpRequest(6L, "ping", null));
		assertEquals(2, r.result().getAsJsonObject().getInt("v"));
		t.close();
	}

	/** 服务端主动请求帧（有 id 无 result/error）被忽略，不影响后续配对。 */
	@Test
	public void serverInitiatedFrameIgnored() throws Exception {
		TestPair p = pair();
		startFakeServer(p, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"some/server/event\"}", 7L, "{\"v\":3}");
		TestTransport t = new TestTransport(p.clientRead, p.clientWrite, Duration.ofSeconds(5));
		McpResponse r = t.sendRequest(new McpRequest(7L, "ping", null));
		assertEquals(3, r.result().getAsJsonObject().getInt("v"));
		t.close();
	}

	/** 关闭传输后再发送抛 AiException。 */
	@Test
	public void closedTransportRejects() throws Exception {
		TestPair p = pair();
		TestTransport t = new TestTransport(p.clientRead, p.clientWrite, Duration.ofSeconds(5));
		t.close();
		try {
			t.sendRequest(new McpRequest(1L, "x", null));
			fail("应抛 AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("已关闭"));
		}
	}

	/** 通知写入失败（输出流已关）抛 AiException。 */
	@Test
	public void sendNotificationWriteFailureThrows() throws Exception {
		TestPair p = pair();
		TestTransport t = new TestTransport(p.clientRead, p.clientWrite, Duration.ofSeconds(5));
		p.clientWrite.close();
		try {
			t.sendNotification(new McpNotification("x"));
			fail("应抛 AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("通知发送失败"));
		} finally {
			t.close();
		}
	}

	/** LineLimitedInputStream 单字节读到 EOF 返回 -1。 */
	@Test
	public void lineLimitedInputStreamSingleByteEof() throws Exception {
		LineFrameMcpTransport.LineLimitedInputStream in = new LineFrameMcpTransport.LineLimitedInputStream(
			new ByteArrayInputStream("ab".getBytes(StandardCharsets.UTF_8)), 10);
		assertEquals('a', in.read());
		assertEquals('b', in.read());
		assertEquals(-1, in.read());
		in.close();
	}

	/** LineLimitedInputStream 批量 read 遇换行重置计数，不超限。 */
	@Test
	public void lineLimitedInputStreamBatchReadResetsOnNewline() throws Exception {
		LineFrameMcpTransport.LineLimitedInputStream in = new LineFrameMcpTransport.LineLimitedInputStream(
			new ByteArrayInputStream("abc\ndef".getBytes(StandardCharsets.UTF_8)), 4);
		byte[] buf = new byte[10];
		int n = in.read(buf, 0, 10);
		assertEquals(7, n);
		in.close();
	}

	/** LineLimitedInputStream 单行超限抛 IOException。 */
	@Test
	public void lineLimitedInputStreamOverflowThrows() {
		try {
			LineFrameMcpTransport.LineLimitedInputStream in = new LineFrameMcpTransport.LineLimitedInputStream(
				new ByteArrayInputStream("aaaaa".getBytes(StandardCharsets.UTF_8)), 2);
			byte[] buf = new byte[10];
			in.read(buf, 0, 10);
			fail("应抛 IOException");
		} catch (java.io.IOException ex) {
			assertTrue(ex.getMessage().contains("exceeds"));
		}
	}

	/** 通知序列化带 params。 */
	@Test
	public void notificationToJsonWithParams() {
		JsonObject p = Json.object();
		p.put("a", 1);
		McpNotification n = new McpNotification("x", p);
		assertEquals(1, n.toJson().getJsonObject("params").getInt("a"));
	}
}
