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
package com.sure.ai.evals.dataset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.evals.EvalException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.Role;

/**
 * {@link JsonlDatasetLoader} 测试。
 */
public class JsonlDatasetLoaderTest {

	private final JsonlDatasetLoader loader = new JsonlDatasetLoader();

	@Test
	public void testStringInput() {
		String jsonl = "{\"id\":\"c1\",\"input\":\"你好\",\"expected\":\"你好世界\",\"tags\":[\"a\",\"b\"]}\n";
		EvalDataset ds = loader.load(new StringReader(jsonl));
		assertEquals(1, ds.size());
		EvalCase c = ds.get(0);
		assertEquals("c1", c.id());
		assertEquals("你好", c.inputText());
		assertEquals("你好世界", c.expected());
		assertEquals(List.of("a", "b"), c.tags());
		assertEquals("jsonl-dataset", ds.name());
	}

	@Test
	public void testMessageArrayWithRoles() {
		String jsonl = "{\"input\":[{\"role\":\"system\",\"content\":\"sys\"},"
			+ "{\"role\":\"user\",\"content\":\"hi\"}],\"expected\":\"ok\"}\n";
		EvalDataset ds = loader.load(new StringReader(jsonl));
		List<ChatMessage> input = ds.get(0).input();
		assertEquals(2, input.size());
		assertEquals(Role.SYSTEM, input.get(0).role());
		assertEquals(Role.USER, input.get(1).role());
	}

	@Test
	public void testAliasesAndExpectedTools() {
		String jsonl = "{\"question\":\"q\",\"answer\":\"a\",\"expectedTools\":[\"search\"]}\n";
		EvalDataset ds = loader.load(new StringReader(jsonl));
		assertEquals("q", ds.get(0).inputText());
		assertEquals("a", ds.get(0).expected());
		assertEquals(List.of("search"), ds.get(0).expectedTools());
	}

	@Test
	public void testMetadataNested() {
		String jsonl = "{\"input\":\"q\",\"metadata\":{\"k\":1,\"nested\":{\"x\":true},\"arr\":[1.5]}}\n";
		EvalDataset ds = loader.load(new StringReader(jsonl));
		Map<String, Object> meta = ds.get(0).metadata();
		assertEquals(1L, meta.get("k"));
		@SuppressWarnings("unchecked")
		Map<String, Object> nested = (Map<String, Object>) meta.get("nested");
		assertEquals(true, nested.get("x"));
	}

	@Test
	public void testBlankLinesAndBadLineSkip() {
		String jsonl = "\n{\"input\":\"q1\"}\n   \n{\"not json\"\n{\"input\":\"q2\"}\n";
		EvalDataset ds = loader.load(new StringReader(jsonl));
		assertEquals(2, ds.size());
		assertEquals("q1", ds.get(0).inputText());
		assertEquals("q2", ds.get(1).inputText());
	}

	@Test
	public void testBadLineFail() {
		JsonlDatasetLoader failLoader = JsonlDatasetLoader.builder()
			.badLineStrategy(JsonlDatasetLoader.BadLineStrategy.FAIL)
			.build();
		assertThrows(EvalException.class,
			() -> failLoader.load(new StringReader("{\"input\":\"q1\"}\n{bad\n")));
	}

	@Test
	public void testNonObjectRootSkips() {
		EvalDataset ds = loader.load(new StringReader("[1,2]\n{\"input\":\"q\"}\n"));
		assertEquals(1, ds.size());
		assertEquals("q", ds.get(0).inputText());
	}

	@Test
	public void testMissingInputSkips() {
		EvalDataset ds = loader.load(new StringReader("{\"expected\":\"x\"}\n{\"input\":\"q\"}\n"));
		assertEquals(1, ds.size());
	}

	@Test
	public void testNullExpected() {
		EvalDataset ds = loader.load(new StringReader("{\"input\":\"q\"}\n"));
		assertNull(ds.get(0).expected());
	}

	@Test
	public void testEmptyFile() {
		EvalDataset ds = loader.load(new StringReader(""));
		assertEquals(0, ds.size());
	}

	@Test
	public void testAssistantMessageItem() {
		String jsonl = "{\"input\":[{\"role\":\"assistant\",\"content\":\"prev\"},\"用户说\"]}\n";
		EvalDataset ds = loader.load(new StringReader(jsonl));
		List<ChatMessage> input = ds.get(0).input();
		assertEquals(2, input.size());
		assertEquals(Role.ASSISTANT, input.get(0).role());
		assertEquals("用户说", input.get(1).content());
		assertTrue(input.get(1).content() != null);
	}
}
