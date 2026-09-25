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
package com.sure.ai.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * 端到端测试：用 ProcessBuilder 启动 {@code EchoMcpServer.java} 子进程，走完整 stdio NDJSON 握手回环。
 *
 * <p>零第三方网络：echo server 仅用 JDK 单文件源码模式（{@code java EchoMcpServer.java}）启动。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public class McpClientE2ETest {

	/** 完整握手 + tools/list + tools/call。 */
	@Test
	public void fullStdioHandshakeLoopback() throws Exception {
		File serverSrc = extractEchoServer();
		String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

		McpClient client = McpClient.stdio(javaBin)
			.arg(serverSrc.getAbsolutePath())
			.timeout(Duration.ofSeconds(15))
			.build();

		try {
			assertEquals(1, client.toolsList().size());
			assertEquals("echo", client.toolsList().get(0).name());

			com.sure.ai.internal.json.JsonObject args = Json.object();
			args.set("text", "hello-mcp");
			McpToolResult r = client.toolsCall("echo", args);
			assertFalse(r.isError());
			assertEquals("echo:hello-mcp", r.asText());
		} finally {
			client.close();
		}
	}

	/** 把测试资源里的 EchoMcpServer.java 拷到临时目录。 */
	private static File extractEchoServer() throws Exception {
		File tmp = File.createTempFile("EchoMcpServer", ".java");
		try (InputStream in = McpClientE2ETest.class.getResourceAsStream("/echo-mcp-server/EchoMcpServer.java")) {
			assertTrue("echo server resource missing", in != null);
			Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return tmp;
	}
}
