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

package com.sure.ai.internal.http;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * {@link SseLineReader} 单元测试。
 *
 * @author sureai
 * @since 0.1.0
 */
public class SseLineReaderTest {

	/** 单 data 行。 */
	@Test
	public void testSingleData() {
		String s = "data: hello\n\n";
		List<SseEvent> out = read(s);
		assertEquals(1, out.size());
		assertEquals("message", out.get(0).event());
		assertEquals("hello", out.get(0).data());
	}

	/** 多行 data 拼接。 */
	@Test
	public void testMultiData() {
		String s = "data: line1\ndata: line2\n\n";
		List<SseEvent> out = read(s);
		assertEquals(1, out.size());
		assertEquals("line1\nline2", out.get(0).data());
	}

	/** event 名。 */
	@Test
	public void testEventName() {
		String s = "event: update\ndata: x\n\n";
		List<SseEvent> out = read(s);
		assertEquals("update", out.get(0).event());
	}

	/** 注释忽略。 */
	@Test
	public void testCommentIgnored() {
		String s = ": keepalive\ndata: x\n\n";
		List<SseEvent> out = read(s);
		assertEquals(1, out.size());
		assertEquals("x", out.get(0).data());
	}

	/** 空行分隔多事件。 */
	@Test
	public void testMultipleEvents() {
		String s = "data: a\n\ndata: b\n\n";
		List<SseEvent> out = read(s);
		assertEquals(2, out.size());
		assertEquals("a", out.get(0).data());
		assertEquals("b", out.get(1).data());
	}

	/** 流结束仍投递未闭合事件。 */
	@Test
	public void testEndOfStream() {
		String s = "data: tail";
		List<SseEvent> out = read(s);
		assertEquals(1, out.size());
		assertEquals("tail", out.get(0).data());
	}

	private static List<SseEvent> read(String s) {
		List<SseEvent> out = new ArrayList<>();
		SseLineReader.read(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)),
			StandardCharsets.UTF_8, out::add);
		return out;
	}
}
