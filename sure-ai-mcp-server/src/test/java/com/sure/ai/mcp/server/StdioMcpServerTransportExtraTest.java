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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * {@link StdioMcpServerTransport} 补测：覆盖空行跳过、handler 异常不中断读循环、
 * 无参构造与 close 吞掉流关闭异常。
 *
 * @author sureai
 * @since 2.6.0
 */
public class StdioMcpServerTransportExtraTest {

	/** close 抛 IOException 的输出流。 */
	private static final class BrokenOut extends java.io.OutputStream {
		@Override
		public void write(int b) {
		}

		@Override
		public void close() throws IOException {
			throw new IOException("boom-out");
		}
	}

	/** close 抛 IOException 的输入流。 */
	private static final class BrokenIn extends java.io.InputStream {
		@Override
		public int read() {
			return -1;
		}

		@Override
		public void close() throws IOException {
			throw new IOException("boom-in");
		}
	}

	/** 无参构造绑定 System.in/out，不启动读线程。 */
	@Test
	public void noArgConstructorBindsSystemStreams() {
		StdioMcpServerTransport t = new StdioMcpServerTransport();
		assertNotNull(t);
		assertFalse(t.isOpen());
	}

	/** 空行被跳过，后续有效帧仍被处理。 */
	@Test
	public void emptyLineSkipped() throws Exception {
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream serverIn = new PipedInputStream(clientOut, 8192);
		PipedOutputStream serverOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(serverOut, 8192);

		AtomicInteger calls = new AtomicInteger();
		StdioMcpServerTransport t = new StdioMcpServerTransport(serverIn, serverOut, 1 << 20);
		t.start(line -> {
			calls.incrementAndGet();
			return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
		});

		clientOut.write(("\n" + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n")
			.getBytes(StandardCharsets.UTF_8));
		clientOut.flush();

		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline && calls.get() < 1) {
			Thread.sleep(20);
		}
		assertEquals(1, calls.get());
		t.close();
		clientIn.close();
	}

	/** handler 抛异常后读循环继续，不崩溃。 */
	@Test
	public void handlerExceptionDoesNotBreakLoop() throws Exception {
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream serverIn = new PipedInputStream(clientOut, 8192);
		PipedOutputStream serverOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(serverOut, 8192);

		AtomicInteger calls = new AtomicInteger();
		StdioMcpServerTransport t = new StdioMcpServerTransport(serverIn, serverOut, 1 << 20);
		t.start(line -> {
			int n = calls.incrementAndGet();
			if (n == 1) {
				throw new IllegalStateException("boom");
			}
			return "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}";
		});

		clientOut.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}\n"
			+ "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"b\"}\n").getBytes(StandardCharsets.UTF_8));
		clientOut.flush();

		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline && calls.get() < 2) {
			Thread.sleep(20);
		}
		assertEquals(2, calls.get());
		assertTrue(t.isOpen());
		t.close();
		clientIn.close();
	}

	/** close 吞掉 in/out 流关闭异常。 */
	@Test
	public void closeSwallowsStreamCloseErrors() {
		StdioMcpServerTransport t = new StdioMcpServerTransport(new BrokenIn(), new BrokenOut(), 1 << 20);
		t.close();
		assertFalse(t.isOpen());
	}
}
