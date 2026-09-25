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

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.Test;

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
}
