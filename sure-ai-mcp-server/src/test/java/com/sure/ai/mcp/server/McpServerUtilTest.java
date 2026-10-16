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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * {@link McpServerUtil} 单例入口测试：覆盖 init/server/resetServer 与 startHttp/startStdio。
 *
 * <p>每个用例结束后 resetServer 避免污染其他测试。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpServerUtilTest {

	/** 重置单例。 */
	@After
	public void tearDown() {
		McpServerUtil.resetServer();
	}

	/** server() 未初始化时懒加载空 server。 */
	@Test
	public void serverLazilyCreated() {
		McpServer server = McpServerUtil.server();
		assertNotNull(server);
	}

	/** init 后 server() 返回注入实例。 */
	@Test
	public void initThenServerReturnsInjected() {
		McpServer custom = new McpServer().serverInfo("custom", "1.0");
		McpServerUtil.init(custom);
		assertSame(custom, McpServerUtil.server());
	}

	/** resetServer 后恢复懒加载。 */
	@Test
	public void resetServerClearsHolder() {
		McpServerUtil.init(new McpServer());
		McpServerUtil.resetServer();
		assertNotNull(McpServerUtil.server());
	}

	/** startHttp 绑定随机端口并返回可关闭的传输。 */
	@Test
	public void startHttpBindsLoopbackPort() throws Exception {
		McpServerUtil.init(new McpServer().serverInfo("util-http", "1.0"));
		HttpMcpServerTransport http = McpServerUtil.startHttp(0);
		assertNotNull(http);
		assertTrue(http.endpoint().startsWith("http://127.0.0.1:"));
		assertTrue(http.getPort() > 0);
		http.close();
	}

	/** startStdio 不抛异常（绑定 System.in 的后台读线程为 daemon）。 */
	@Test
	public void startStdioStartsWithoutError() {
		McpServerUtil.init(new McpServer().serverInfo("util-stdio", "1.0"));
		McpServerUtil.startStdio();
		assertNotNull(McpServerUtil.server());
	}

	/** 私有构造器应抛 AssertionError。 */
	@Test
	public void privateConstructorThrows() {
		try {
			var ctor = McpServerUtil.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			ctor.newInstance();
			assertTrue("应抛 AssertionError", false);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			assertEquals(AssertionError.class, ex.getCause().getClass());
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}
}
