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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.mcp.McpClient;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * Stdio 服务端传输回环测试：用管道把 {@link StdioMcpServerTransport} 与
 * {@link PipeMcpTransport} 对接，跑真实 {@link McpClient} 的 initialize→tools/list→tools/call。
 *
 * <p>零真实网络，全在 JVM 内管道完成。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
public class StdioLoopbackTest {

	@Test
	public void fullHandshakeLoopback() throws Exception {
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream serverIn = new PipedInputStream(clientOut, 8192);
		PipedOutputStream serverOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(serverOut, 8192);

		McpServer server = new McpServer().serverInfo("e2e-stdio", "1.0.0");
		server.registerTool(new McpServerTool("echo", "echo back", Json.object(),
			args -> new McpToolResult(false, List.of("echo:" + args.optString("text", "")))));
		StdioMcpServerTransport stdio = new StdioMcpServerTransport(serverIn, serverOut, 1 << 20);
		server.start(stdio);

		try (McpClient client = McpClient.stdio("unused")
			.transport(new PipeMcpTransport(clientIn, clientOut)).build()) {
			assertEquals(1, client.toolsList().size());
			assertEquals("echo", client.toolsList().get(0).name());

			com.sure.ai.internal.json.JsonObject args = Json.object();
			args.put("text", "hello-mcp");
			McpToolResult r = client.toolsCall("echo", args);
			assertFalse(r.isError());
			assertEquals("echo:hello-mcp", r.asText());
		} finally {
			server.close();
		}
	}

	@Test
	public void oversizedFrameRejected() throws Exception {
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream serverIn = new PipedInputStream(clientOut, 65536);
		PipedOutputStream serverOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(serverOut, 65536);

		McpServer server = new McpServer();
		StdioMcpServerTransport stdio = new StdioMcpServerTransport(serverIn, serverOut, 32);
		server.start(stdio);

		try {
			// 一行远超 32 字节上限：写入后服务端读循环应结束（open=false），不 OOM
			String huge = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"pad\":\""
				+ repeat("x", 4096) + "\"}}";
			clientOut.write((huge + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			clientOut.flush();

			long deadline = System.currentTimeMillis() + 3000;
			boolean closed = false;
			while (System.currentTimeMillis() < deadline) {
				if (!stdio.isOpen()) {
					closed = true;
					break;
				}
				Thread.sleep(20);
			}
			assertTrue("oversized frame should close read loop", closed);
		} finally {
			server.close();
			clientOut.close();
			clientIn.close();
		}
	}

	private static String repeat(String s, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			sb.append(s);
		}
		return sb.toString();
	}
}
