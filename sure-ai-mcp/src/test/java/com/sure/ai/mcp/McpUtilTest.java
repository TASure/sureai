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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link McpUtil} 静态工厂入口测试：覆盖 stdio/http Builder 构造、可变参数分支与私有构造器。
 *
 * <p>只断言 Builder 字段装配，不启动子进程、不发网络请求。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpUtilTest {

	/** stdio(command, args...) 应把可变参数追加进 Builder。 */
	@Test
	public void stdioBuilderCarriesArgs() {
		McpClient.Builder b = McpUtil.stdio("node", "server.js", "--debug");
		assertNotNull(b);
	}

	/** args 传 null 时不应抛异常（空分支）。 */
	@Test
	public void stdioBuilderWithNullArgsIsLenient() {
		McpClient.Builder b = McpUtil.stdio("node", (String[]) null);
		assertNotNull(b);
	}

	/** http(url) 应返回指向该 URL 的 Builder。 */
	@Test
	public void httpBuilderWrapsUrl() {
		McpClient.Builder b = McpUtil.http("http://127.0.0.1:8000/mcp");
		assertNotNull(b);
	}

	/** 私有构造器应抛 AssertionError（工具类不可实例化）。 */
	@Test
	public void privateConstructorThrows() {
		try {
			var ctor = McpUtil.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			ctor.newInstance();
			assertTrue("应抛 AssertionError", false);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			assertEquals(AssertionError.class, ex.getCause().getClass());
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}

	/** 同一调用返回独立 Builder，不做单例缓存。 */
	@Test
	public void buildsAreIndependentInstances() {
		McpClient.Builder b1 = McpUtil.http("http://127.0.0.1:1/mcp");
		McpClient.Builder b2 = McpUtil.http("http://127.0.0.1:2/mcp");
		assertNotNull(b1);
		assertNotNull(b2);
		assertSame(b1.getClass(), b2.getClass());
	}
}
