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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.exception.AiException;
import com.sure.tool.lang.Assert;

/**
 * stdio 传输：用 {@link ProcessBuilder} 启动 MCP server 子进程，通过 stdin/stdout 交换 NDJSON 帧。
 *
 * <p>stderr 由后台线程持续排空并丢弃（MCP server 的日志约定写 stderr，不影响协议）。
 * 关闭时 destroy 子进程。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class StdioMcpTransport extends LineFrameMcpTransport {

	private final Process process;

	/** 私有构造器：只启动一次进程。 */
	private StdioMcpTransport(Process process, Duration timeout) {
		super(process.getInputStream(), process.getOutputStream(), timeout);
		this.process = process;
		drainStderr(process);
	}

	/** 启动进程一次，返回它。 */
	private static Process launch(String command, List<String> args, Map<String, String> env) {
		Assert.notBlank(command, "command must not be blank");
		List<String> cmd = new ArrayList<>();
		cmd.add(command);
		if (args != null) {
			cmd.addAll(args);
		}
		ProcessBuilder pb = new ProcessBuilder(cmd);
		if (env != null) {
			pb.environment().putAll(env);
		}
		try {
			return pb.start();
		} catch (IOException ex) {
			throw new AiException("无法启动 MCP 子进程: " + command + " " + args, ex);
		}
	}

	/** 后台排空 stderr。 */
	private static void drainStderr(Process process) {
		Thread t = new Thread(() -> {
			try (InputStream err = process.getErrorStream()) {
				err.transferTo(java.io.OutputStream.nullOutputStream());
			} catch (IOException ex) {
				// 进程退出后 stderr 关闭属正常
			}
		}, "sureai-mcp-stderr-drain");
		t.setDaemon(true);
		t.start();
	}

	@Override
	protected void doClose() {
		if (this.process != null && this.process.isAlive()) {
			this.process.destroy();
			try {
				this.process.waitFor();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * 便捷 Builder。
	 *
	 * @return 新 Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * stdio 传输配置 Builder。
	 */
	public static final class Builder {

		private String command;
		private final List<String> args = new ArrayList<>();
		private final Map<String, String> env = new LinkedHashMap<>();
		private Duration timeout = DEFAULT_TIMEOUT;

		/** 私有构造器。 */
		private Builder() {
		}

		/**
		 * 设置可执行命令。
		 *
		 * @param command 命令
		 * @return this
		 */
		public Builder command(String command) {
			this.command = command;
			return this;
		}

		/**
		 * 追加一个命令参数。
		 *
		 * @param arg 参数
		 * @return this
		 */
		public Builder arg(String arg) {
			this.args.add(arg);
			return this;
		}

		/**
		 * 追加多个命令参数。
		 *
		 * @param more 参数列表
		 * @return this
		 */
		public Builder args(List<String> more) {
			if (more != null) {
				this.args.addAll(more);
			}
			return this;
		}

		/**
		 * 设置环境变量。
		 *
		 * @param name  变量名
		 * @param value 变量值
		 * @return this
		 */
		public Builder env(String name, String value) {
			this.env.put(name, value);
			return this;
		}

		/**
		 * 批量设置环境变量。
		 *
		 * @param vars 变量映射
		 * @return this
		 */
		public Builder env(Map<String, String> vars) {
			if (vars != null) {
				this.env.putAll(vars);
			}
			return this;
		}

		/**
		 * 设置请求超时。
		 *
		 * @param timeout 超时
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 启动子进程并构建传输。
		 *
		 * @return stdio 传输
		 */
		public StdioMcpTransport build() {
			return new StdioMcpTransport(launch(this.command, this.args, this.env), this.timeout);
		}
	}
}
