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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.time.Duration;

import org.junit.Test;

/**
 * {@link StdioMcpTransport} 关闭行为测试：重点验证 P1-4 子进程忽略 SIGTERM 时 close() 不永久阻塞。
 *
 * @author sureai
 * @since 1.2.1
 */
public class StdioMcpTransportTest {

	/**
	 * P1-4：子进程忽略 SIGTERM 时，close() 应在 5s 超时后 destroyForcibly 强杀，不永久阻塞。
	 *
	 * <p>用 {@code sh -c "trap '' TERM; sleep infinity"} 启动一个忽略 SIGTERM 的 shell。
	 * 若环境无 sh（如 Windows）则跳过。</p>
	 */
	@Test
	public void closeForceKillsProcessIgnoringSigTerm() throws Exception {
		// 仅在有 sh 的环境运行
		boolean hasSh;
		try {
			new ProcessBuilder("sh", "-c", "true").start().waitFor();
			hasSh = true;
		} catch (IOException | InterruptedException ex) {
			hasSh = false;
		}
		assumeTrue("当前环境无 sh，跳过 SIGTERM 强杀测试", hasSh);

		ProcessBuilder pb = new ProcessBuilder("sh", "-c", "trap '' TERM; sleep infinity");
		pb.redirectErrorStream(false);
		Process p;
		try {
			p = pb.start();
		} catch (IOException ex) {
			assumeTrue("无法启动 sh 子进程，跳过", false);
			return;
		}

		StdioMcpTransport t = new StdioMcpTransport(p, Duration.ofSeconds(5));
		assertTrue("初始进程应存活", p.isAlive());

		long start = System.nanoTime();
		t.close();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		// 应在 ~5s（destroy 等待）+ 少量强杀时间内返回，绝不能永久阻塞
		assertTrue("close() 耗时过长（" + elapsedMs + "ms），可能未强杀", elapsedMs < 20_000L);
		assertFalse("close() 后进程应被强杀", p.isAlive());

		// 清理可能的孤儿 sleep 子进程
		p.descendants().forEach(h -> h.destroyForcibly());
	}

	/** 正常子进程收到 SIGTERM 后快速退出，close() 不应触发强杀路径。 */
	@Test
	public void closeNormalProcessReturnsQuickly() throws Exception {
		ProcessBuilder pb = new ProcessBuilder("sh", "-c", "exit 0");
		Process p;
		try {
			p = pb.start();
		} catch (IOException ex) {
			assumeTrue("无 sh 环境，跳过", false);
			return;
		}
		// 等进程自己退出
		p.waitFor();

		StdioMcpTransport t = new StdioMcpTransport(p, Duration.ofSeconds(5));
		long start = System.nanoTime();
		t.close();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		// 进程已死，doClose 直接返回
		assertTrue("正常进程 close 不应耗时", elapsedMs < 5_000L);
		assertFalse(p.isAlive());
	}
}
