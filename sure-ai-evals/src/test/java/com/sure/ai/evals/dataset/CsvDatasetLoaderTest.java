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

import org.junit.Test;

import com.sure.ai.evals.EvalException;

/**
 * {@link CsvDatasetLoader} 测试。
 */
public class CsvDatasetLoaderTest {

	private final CsvDatasetLoader loader = new CsvDatasetLoader();

	@Test
	public void testBasicMapping() {
		String csv = "id,input,expected,tags\n"
			+ "c1,你好,你好世界,中文;问候\n"
			+ "c2,问题,答案,单标签\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals(2, ds.size());
		EvalCase c1 = ds.get(0);
		assertEquals("c1", c1.id());
		assertEquals("你好", c1.inputText());
		assertEquals("你好世界", c1.expected());
		assertEquals(List.of("中文", "问候"), c1.tags());
		assertEquals("csv-dataset", ds.name());
	}

	@Test
	public void testAliases() {
		String csv = "question,answer,reference\n"
			+ "q1,期望答案,另一参考\n";
		EvalDataset ds = new CsvDatasetLoader().load(new StringReader(csv));
		EvalCase c = ds.get(0);
		assertEquals("q1", c.inputText());
		assertEquals("期望答案", c.expected());
	}

	@Test
	public void testQuotedCommaNewlineAndEscape() {
		String csv = "input,expected\n"
			+ "\"含有,逗号\",\"第一行\n第二行，带引号 \"\"嘿嘿\"\"\"\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals(1, ds.size());
		EvalCase c = ds.get(0);
		assertEquals("含有,逗号", c.inputText());
		assertTrue(c.expected().startsWith("第一行\n第二行，带引号 \"嘿嘿\""));
	}

	@Test
	public void testCrlfAndBom() {
		String csv = "﻿id,input,expected\r\n1,a,b\r\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals(1, ds.size());
		assertEquals("1", ds.get(0).id());
		assertEquals("a", ds.get(0).inputText());
	}

	@Test
	public void testBlankLinesSkipped() {
		String csv = "id,input\n\n1,a\n\n\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals(1, ds.size());
	}

	@Test
	public void testUnknownColumnToMetadata() {
		String csv = "input,expected,topic\n"
			+ "q,a,数学\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals("数学", ds.get(0).metadata().get("topic"));
	}

	@Test
	public void testRaggedRowPaddedAndExtra() {
		String csv = "id,input,expected,tags\n"
			+ "1,只有输入\n"
			+ "2,输入,期望,标签,额外\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals(2, ds.size());
		assertNull(ds.get(0).expected());
		assertEquals("额外", ds.get(1).metadata().get("extra_4"));
	}

	@Test
	public void testExpectedToolsColumn() {
		String csv = "input,expectedTools\n"
			+ "q,search;calc\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals(List.of("search", "calc"), ds.get(0).expectedTools());
	}

	@Test
	public void testEmptyInputRowSkipsByDefault() {
		String csv = "id,input\n"
			+ "1,\n"
			+ "2,有效\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertEquals(1, ds.size());
		assertEquals("2", ds.get(0).id());
	}

	@Test
	public void testEmptyInputRowFailsWhenStrategyFail() {
		CsvDatasetLoader failLoader = CsvDatasetLoader.builder()
			.badLineStrategy(CsvDatasetLoader.BadLineStrategy.FAIL)
			.build();
		String csv = "id,input\n1,\n";
		assertThrows(EvalException.class, () -> failLoader.load(new StringReader(csv)));
	}

	@Test
	public void testUnterminatedQuoteThrows() {
		String csv = "input\n\"未闭合\n";
		assertThrows(EvalException.class, () -> loader.load(new StringReader(csv)));
	}

	@Test
	public void testEmptyFile() {
		EvalDataset ds = loader.load(new StringReader(""));
		assertEquals(0, ds.size());
	}

	@Test
	public void testCustomSeparatorAndName() {
		CsvDatasetLoader custom = CsvDatasetLoader.builder()
			.name("my-csv").tagsSeparator("|").build();
		String csv = "input,tags\nq,甲|乙\n";
		EvalDataset ds = custom.load(new StringReader(csv));
		assertEquals("my-csv", ds.name());
		assertEquals(List.of("甲", "乙"), ds.get(0).tags());
	}

	@Test
	public void testNullExpectedWhenColumnBlank() {
		String csv = "input,expected\nq,\n";
		EvalDataset ds = loader.load(new StringReader(csv));
		assertNull(ds.get(0).expected());
	}

	@Test
	public void testParseRowsDirectly() {
		List<List<String>> rows = CsvDatasetLoader.parseRows("a,b\n\"x,y\",z");
		assertEquals(2, rows.size());
		assertEquals("x,y", rows.get(1).get(0));
		assertEquals(List.of("a", "b"), rows.get(0));
	}
}
