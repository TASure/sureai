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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

	/** OpenAI 协议 [DONE] 标记：作为普通 data 事件投递，由上层 consumer 过滤。 */
	@Test
	public void testDoneMarker() {
		String s = "data: {\"choices\":[]}\n\ndata: [DONE]\n\n";
		List<SseEvent> out = read(s);
		assertEquals(2, out.size());
		assertEquals("[DONE]", out.get(1).data());
	}

	/** malformed JSON：SseLineReader 不解析 JSON，原样把 data 文本投递给 consumer。 */
	@Test
	public void testMalformedJsonDeliveredVerbatim() {
		String s = "data: {not valid json\n\n";
		List<SseEvent> out = read(s);
		assertEquals(1, out.size());
		assertEquals("{not valid json", out.get(0).data());
	}

	/** 空响应体：0 个事件。 */
	@Test
	public void testEmptyBody() {
		List<SseEvent> out = read("");
		assertTrue(out.isEmpty());
	}

	/** 仅注释行（keepalive）：无 data，不投递任何事件。 */
	@Test
	public void testOnlyComments() {
		String s = ": keepalive\n: ping\n";
		List<SseEvent> out = read(s);
		assertTrue(out.isEmpty());
	}

	/** 跨缓冲区分块：BufferedReader 自动跨行聚合（模拟 chunked 传输）。 */
	@Test
	public void testChunkedReassembly() {
		// 用一个每次只返回 3 字节的 InputStream，模拟网络分块
		byte[] all = "data: chunked-event\n\n".getBytes(StandardCharsets.UTF_8);
		InputStream in = new InputStream() {
			private int pos;
			@Override
			public int read() {
				if (pos >= all.length) {
					return -1;
				}
				return all[pos++] & 0xFF;
			}
			@Override
			public int read(byte[] b, int off, int len) {
				if (pos >= all.length) {
					return -1;
				}
				int n = Math.min(3, Math.min(len, all.length - pos));
				System.arraycopy(all, pos, b, off, n);
				pos += n;
				return n;
			}
		};
		List<SseEvent> out = new ArrayList<>();
		SseLineReader.read(in, StandardCharsets.UTF_8, out::add);
		assertEquals(1, out.size());
		assertEquals("chunked-event", out.get(0).data());
	}

	private static List<SseEvent> read(String s) {
		List<SseEvent> out = new ArrayList<>();
		SseLineReader.read(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)),
			StandardCharsets.UTF_8, out::add);
		return out;
	}
}
