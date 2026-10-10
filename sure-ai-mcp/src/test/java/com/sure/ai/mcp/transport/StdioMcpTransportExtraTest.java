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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link StdioMcpTransport} Builder 补测：覆盖 arg/env/envMap 链式 setter、
 * 命令不存在时 launch 抛 AiException，以及正常 build 路径。
 *
 * @author sureai
 * @since 2.6.0
 */
public class StdioMcpTransportExtraTest {

	/** Builder 链式 setter 返回自身。 */
	@Test
	public void builderSettersChain() {
		StdioMcpTransport.Builder b = StdioMcpTransport.builder()
			.command("echo")
			.arg("hello")
			.args(List.of("world"))
			.env("KEY", "VAL")
			.env(Map.of("K2", "V2"))
			.timeout(Duration.ofSeconds(3));
		assertNotNull(b);
	}

	/** 命令不存在时 build 抛 AiException（launch IOException 分支）。 */
	@Test(expected = AiException.class)
	public void launchNonexistentCommandThrows() {
		StdioMcpTransport.builder()
			.command("/sureai-nonexistent-binary-xyz-12345")
			.build();
	}

	/** 正常 build：sh 立即退出，覆盖完整构造路径。 */
	@Test
	public void buildHappyPath() throws Exception {
		boolean hasSh;
		try {
			new ProcessBuilder("sh", "-c", "true").start().waitFor();
			hasSh = true;
		} catch (IOException | InterruptedException ex) {
			hasSh = false;
		}
		assumeTrue("无 sh 环境，跳过", hasSh);
		StdioMcpTransport t = StdioMcpTransport.builder()
			.command("sh")
			.arg("-c")
			.arg("exit 0")
			.env("HOME", "/tmp")
			.build();
		assertNotNull(t);
		t.close();
	}
}
