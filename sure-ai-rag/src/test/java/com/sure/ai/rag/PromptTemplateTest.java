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

package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.prompt.PromptTemplate;

/**
 * {@link PromptTemplate} 单元测试。
 *
 * @author sureai
 * @since 1.1.0
 */
public class PromptTemplateTest {

	@Test
	public void testBasicReplacement() {
		PromptTemplate t = PromptTemplate.fromString("Hello {name}, age {age}");
		Map<String, Object> vars = new HashMap<>();
		vars.put("name", "Alice");
		vars.put("age", 30);
		assertEquals("Hello Alice, age 30", t.render(vars));
	}

	@Test
	public void testDefaultValue() {
		PromptTemplate t = PromptTemplate.fromString("{greeting=Hello} {name}");
		assertEquals("Hello Bob", t.render(Map.of("name", "Bob")));
	}

	@Test
	public void testStrictModeMissingVar() {
		PromptTemplate t = PromptTemplate.builder().template("Hi {name}").strict(true).build();
		try {
			t.render(Map.of());
			fail("应抛异常");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("name"));
		}
	}

	@Test
	public void testNonStrictKeepsPlaceholder() {
		PromptTemplate t = PromptTemplate.fromString("Hi {name}");
		assertEquals("Hi {name}", t.render(Map.of()));
	}

	@Test
	public void testStrictModeExtraVar() {
		PromptTemplate t = PromptTemplate.builder().template("Hi {name}").strict(true).build();
		try {
			t.render(Map.of("name", "A", "extra", "X"));
			fail("应抛异常");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("extra"));
		}
	}

	@Test
	public void testVarargsRender() {
		PromptTemplate t = PromptTemplate.fromString("{name} is {age}");
		assertEquals("Alice is 30", t.render("name", "Alice", "age", "30"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testVarargsOddCount() {
		PromptTemplate.fromString("{a}").render("a", "1", "b");
	}

	@Test
	public void testFromResource() {
		PromptTemplate t = PromptTemplate.fromResource("prompts/sample.txt");
		Map<String, Object> vars = new HashMap<>();
		vars.put("name", "张三");
		vars.put("product", "sureai");
		String out = t.render(vars);
		assertTrue(out.contains("你好 张三，欢迎使用 sureai。"));
		assertTrue(out.contains("默认角色：访客。"));
	}

	@Test
	public void testVariablesExtraction() {
		PromptTemplate t = PromptTemplate.fromString("{a} and {b=1} and {a}");
		List<String> vars = t.variables();
		assertEquals(Arrays.asList("a", "b"), vars);
	}

	@Test
	public void testNullValue() {
		PromptTemplate t = PromptTemplate.fromString("[{name}]");
		Map<String, Object> vars = new HashMap<>();
		vars.put("name", null);
		assertEquals("[]", t.render(vars));
	}

	@Test
	public void testNonNullToString() {
		PromptTemplate t = PromptTemplate.fromString("[{n}]");
		assertEquals("[42]", t.render(Map.of("n", 42)));
	}

	@Test
	public void testNonStrictExtraVarIgnored() {
		PromptTemplate t = PromptTemplate.fromString("Hi {name}");
		assertEquals("Hi A", t.render(Map.of("name", "A", "ghost", "G")));
	}

	@Test
	public void testEmptyMapNonStrictKeepsAll() {
		PromptTemplate t = PromptTemplate.fromString("{x}={y=def}");
		assertEquals("{x}=def", t.render(Map.of()));
	}

	@Test
	public void testNoPlaceholder() {
		PromptTemplate t = PromptTemplate.fromString("plain text");
		assertTrue(t.variables().isEmpty());
		assertEquals("plain text", t.render(Map.of("x", 1)));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBlankTemplateRejected() {
		PromptTemplate.builder().template("").build();
	}

	@Test(expected = IllegalArgumentException.class)
	public void testMissingResource() {
		PromptTemplate.fromResource("prompts/not-exist.txt");
	}

	@Test
	public void testNullVariablesMap() {
		PromptTemplate t = PromptTemplate.fromString("Hi {name=X}");
		assertEquals("Hi X", t.render((Map<String, Object>) null));
	}

	@Test
	public void testStrictKeepsNoExtraNorMissing() {
		PromptTemplate t = PromptTemplate.builder().template("Hi {name}").strict(true).build();
		assertEquals("Hi A", t.render(Map.of("name", "A")));
	}

	@Test
	public void testVariablesUnmodifiable() {
		PromptTemplate t = PromptTemplate.fromString("{a}");
		try {
			t.variables().add("x");
			fail("应不可修改");
		} catch (UnsupportedOperationException expected) {
			assertTrue(true);
		}
	}
}
