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

package {{mainPackage}};

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.util.JsonMapper;

/**
 * 代码助手模板最小单元测试（零真实网络：静态检查规则 + JSON 反序列化）。
 */
public class MainTest {

	@Test
	public void cleanCodePasses() {
		assertEquals("OK", Main.checkCode("class Demo {\n\tvoid run() {\n\t}\n}"));
	}

	@Test
	public void detectsUnclosedQuote() {
		String result = Main.checkCode("String s = \"hello;");
		assertTrue(result.contains("引号未闭合"));
		assertTrue(result.contains("第 1 行"));
	}

	@Test
	public void detectsUnindentedMember() {
		String result = Main.checkCode("void run() {\n}");
		assertTrue(result.contains("未缩进"));
	}

	@Test
	public void emptyCodeIsReported() {
		assertTrue(Main.checkCode(null).contains("为空"));
		assertTrue(Main.checkCode("  ").contains("为空"));
	}

	@Test
	public void multiLineProblemsAreNumbered() {
		String result = Main.checkCode("void a() {\n\tString s = \"x;\n\tvoid b() {\n}");
		assertTrue(result.contains("第 1 行"));
		assertTrue(result.contains("第 2 行"));
	}

	@Test
	public void jsonMapperParsesStructuredRecord() {
		String json = "{\"verdict\":\"pass\",\"issues\":0,\"summary\":\"无问题\"}";
		Main.ReviewResult result = JsonMapper.fromJson(
			Json.parse(json).getAsJsonObject(), Main.ReviewResult.class);
		assertEquals("pass", result.verdict());
		assertEquals(0, result.issues());
		assertEquals("无问题", result.summary());
	}
}
