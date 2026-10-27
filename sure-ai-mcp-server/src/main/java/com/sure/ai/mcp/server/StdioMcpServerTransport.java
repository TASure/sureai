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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * stdio 服务端传输：从 {@code System.in} 逐行读 NDJSON，把每行交给协议引擎，响应写到 {@code System.out}。
 *
 * <p><b>约定</b>：stdout 是 JSON-RPC 专属通道，所有日志/异常一律走 stderr，避免污染协议帧。
 * 单帧（一行）上限默认 64MB，超限即视为恶意帧并结束读取，防止 BufferedReader 无限缓冲 OOM。</p>
 *
 * <p>生产用法（启动后阻塞在后台读线程）：</p>
 * <pre>{@code
 * McpServer server = new McpServer().registerTool(...);
 * server.start(new StdioMcpServerTransport());
 * }</pre>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class StdioMcpServerTransport implements AutoCloseable {

	/** 默认单帧最大字节数：64MB。 */
	public static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024 * 1024;

	private final InputStream in;
	private final OutputStream out;
	private final int maxLineBytes;
	private final boolean ownsStreams;
	private final Thread reader;
	private volatile boolean open;
	private volatile McpFrameHandler currentHandler;

	/**
	 * 绑定 {@code System.in}/{@code System.out} 的构造。
	 */
	public StdioMcpServerTransport() {
		this(System.in, System.out, DEFAULT_MAX_LINE_BYTES, false);
	}

	/**
	 * 测试用：绑定任意流。
	 *
	 * @param in           入站帧来源
	 * @param out          出站响应去向
	 * @param maxLineBytes 单行上限
	 */
	StdioMcpServerTransport(InputStream in, OutputStream out, int maxLineBytes) {
		this(in, out, maxLineBytes, true);
	}

	private StdioMcpServerTransport(InputStream in, OutputStream out, int maxLineBytes, boolean owns) {
		this.in = in;
		this.out = out;
		this.maxLineBytes = maxLineBytes;
		this.ownsStreams = owns;
		this.reader = new Thread(this::readLoop, "sureai-mcp-server-stdio");
		this.reader.setDaemon(true);
	}

	/**
	 * 启动读线程并开始 dispatch。
	 *
	 * @param handler 协议引擎
	 */
	public void start(McpFrameHandler handler) {
		this.currentHandler = handler;
		this.open = true;
		this.reader.start();
	}

	/** 读循环：逐行 dispatch，响应写回。 */
	private void readLoop() {
		BufferedReader br = new BufferedReader(new InputStreamReader(
			new LineLimitedInputStream(this.in, this.maxLineBytes), StandardCharsets.UTF_8));
		try {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				String resp;
				try {
					resp = this.currentHandler.handle(line);
				} catch (RuntimeException ex) {
					log("handler error: " + ex.getMessage());
					continue;
				}
				if (resp != null) {
					writeLine(resp);
				}
			}
		} catch (IOException ex) {
			if (this.open) {
				log("stdio read ended: " + ex.getMessage());
			}
		} finally {
			this.open = false;
		}
	}

	/** 同步写一行并 flush。 */
	private synchronized void writeLine(String line) throws IOException {
		byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
		this.out.write(bytes);
		this.out.flush();
	}

	private static void log(String msg) {
		PrintStream err = System.err;
		err.println("[sureai-mcp-server] " + msg);
	}

	@Override
	public void close() {
		this.open = false;
		try {
			this.in.close();
		} catch (IOException ex) {
			// ignore
		}
		if (this.ownsStreams) {
			try {
				this.out.close();
			} catch (IOException ex) {
				// ignore
			}
		}
	}

	/**
	 * 传输是否仍在读取。
	 *
	 * @return 在读返回 true
	 */
	public boolean isOpen() {
		return this.open;
	}

	/**
	 * 限长行输入流：统计每行字节数，超过上限抛 IOException。
	 */
	static final class LineLimitedInputStream extends InputStream {

		private final InputStream in;
		private final int maxLineBytes;
		private int lineBytes;

		LineLimitedInputStream(InputStream in, int maxLineBytes) {
			this.in = in;
			this.maxLineBytes = maxLineBytes;
		}

		@Override
		public int read() throws IOException {
			int b = this.in.read();
			if (b == -1) {
				return -1;
			}
			count(b);
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int n = this.in.read(b, off, len);
			if (n == -1) {
				return -1;
			}
			for (int i = 0; i < n; i++) {
				count(b[off + i] & 0xFF);
			}
			return n;
		}

		private void count(int b) throws IOException {
			if (b == '\n') {
				this.lineBytes = 0;
			} else {
				this.lineBytes++;
				if (this.lineBytes > this.maxLineBytes) {
					throw new IOException("MCP frame exceeds max size (" + this.maxLineBytes + " bytes)");
				}
			}
		}

		@Override
		public void close() throws IOException {
			this.in.close();
		}
	}
}
