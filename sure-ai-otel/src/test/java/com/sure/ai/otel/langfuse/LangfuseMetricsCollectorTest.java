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

package com.sure.ai.otel.langfuse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

/**
 * {@link LangfuseMetricsCollector} 映射单测：用录制型 {@link LangfuseBatchSender} 捕获批次，
 * 断言一次 LLM 调用被组装为 trace-create + generation-create + generation-update（+ 重试事件），
 * 零真实网络。
 *
 * @author sureai
 * @since 2.4.0
 */
public class LangfuseMetricsCollectorTest {

	/** 录制发送器：记录每次 send 的事件列表。 */
	private static List<List<String>> recordSends(LangfuseMetricsCollector[] holder,
			List<List<String>> sink) {
		LangfuseBatchSender recorder = events -> {
			sink.add(Collections.unmodifiableList(new ArrayList<>(events)));
			return CompletableFuture.completedFuture(null);
		};
		holder[0] = new LangfuseMetricsCollector(true, recorder, "production");
		return sink;
	}

	private static LangfuseMetricsCollector newCollector(List<List<String>> sink) {
		LangfuseMetricsCollector[] holder = new LangfuseMetricsCollector[1];
		recordSends(holder, sink);
		return holder[0];
	}

	/** 成功链路：trace-create + generation-create + generation-update(DEFAULT, usage, model)。 */
	@Test
	public void testSuccessMapsToTraceAndGeneration() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = newCollector(sends);

		c.onRequestStart("/v1/chat/completions");
		c.onTokenUsage("gpt-4o", 12, 8, 20);
		c.onRequestSuccess("/v1/chat/completions", 200, 350);

		assertEquals(1, sends.size());
		List<String> batch = sends.get(0);
		assertEquals(3, batch.size());
		assertTrue(batch.get(0).contains("\"type\":\"trace-create\""));
		assertTrue(batch.get(1).contains("\"type\":\"generation-create\""));
		assertTrue(batch.get(2).contains("\"type\":\"generation-update\""));

		// trace-create body.id 与 generation-create.traceId 一致
		assertTrue(batch.get(0), batch.get(0).contains("\"id\":\"trace-"));
		String traceId = extract(batch.get(0), "\"id\":\"(trace-[^\"]+)\"");
		assertTrue("generation-create links traceId=" + traceId,
			batch.get(1).contains("\"traceId\":\"" + traceId + "\""));

		// generation-update：DEFAULT / model / usage / endTime
		String update = batch.get(2);
		assertTrue(update.contains("\"level\":\"DEFAULT\""));
		assertTrue(update.contains("\"statusMessage\":\"HTTP 200\""));
		assertTrue(update.contains("\"model\":\"gpt-4o\""));
		assertTrue(update.contains("\"usage\":{\"input\":12,\"output\":8,\"total\":20}"));
		assertTrue(update.contains("\"endTime\""));

		// environment 出现在 trace-create
		assertTrue(batch.get(0).contains("\"environment\":\"production\""));
	}

	/** 失败链路：generation-update 为 ERROR，statusMessage 取异常。 */
	@Test
	public void testFailureMapsToErrorGeneration() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = newCollector(sends);

		c.onRequestStart("/v1/chat");
		c.onRequestFailure("/v1/chat", -1, new IllegalStateException("boom"), 90);

		assertEquals(1, sends.size());
		List<String> batch = sends.get(0);
		assertEquals(3, batch.size());
		String update = batch.get(2);
		assertTrue(update.contains("\"type\":\"generation-update\""));
		assertTrue(update.contains("\"level\":\"ERROR\""));
		assertTrue(update.contains("\"statusMessage\":\"IllegalStateException: boom\""));
		// 无 usage 时不写 usage 字段
		assertTrue(!update.contains("\"usage\""));
	}

	/** 失败仅 HTTP 状态码（无异常）：statusMessage 取 HTTP 码。 */
	@Test
	public void testFailureHttpStatusMessage() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = newCollector(sends);
		c.onRequestStart("/v1/chat");
		c.onRequestFailure("/v1/chat", 500, null, 90);
		assertTrue(sends.get(0).get(2).contains("\"statusMessage\":\"HTTP 500\""));
	}

	/** 重试：追加 event-create(attempt/httpStatus)，挂在该 trace/generation 下。 */
	@Test
	public void testRetryBecomesEvent() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = newCollector(sends);

		c.onRequestStart("/v1/chat");
		c.onRetry("/v1/chat", 1, 429);
		c.onRetry("/v1/chat", 2, 429);
		c.onRequestSuccess("/v1/chat", 200, 500);

		List<String> batch = sends.get(0);
		// trace-create + generation-create + 2 event-create + generation-update = 5
		assertEquals(5, batch.size());
		int eventCount = 0;
		for (String e : batch) {
			if (e.contains("\"type\":\"event-create\"")) {
				eventCount++;
				assertTrue(e.contains("\"name\":\"retry\""));
			}
		}
		assertEquals(2, eventCount);
		assertTrue(batch.get(2).contains("\"attempt\":1"));
		assertTrue(batch.get(2).contains("\"httpStatus\":429"));
	}

	/** 禁用态：sender 零调用，全方法空操作不抛异常。 */
	@Test
	public void testDisabledNoops() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = new LangfuseMetricsCollector(false,
			ev -> {
				sends.add(ev);
				return CompletableFuture.completedFuture(null);
			}, null);
		c.onRequestStart("/v1/chat");
		c.onTokenUsage("m", 1, 1, 2);
		c.onRetry("/v1/chat", 1, 429);
		c.onRequestSuccess("/v1/chat", 200, 1);
		c.onRequestFailure("/v1/chat", 500, null, 1);
		assertEquals(0, sends.size());
	}

	/** 未 start 直接终态：空操作不抛异常。 */
	@Test
	public void testTerminalWithoutStartNoop() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = newCollector(sends);
		c.onRequestSuccess("/x", 200, 1);
		c.onRequestFailure("/x", 500, null, 1);
		c.onTokenUsage("m", 1, 1, 2);
		assertEquals(0, sends.size());
	}

	/** 上一次未收尾（异常泄漏）：再次 start 时按 ERROR 收尾旧批次，再开新批次。 */
	@Test
	public void testLeakedInFlightFlushedOnNextStart() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = newCollector(sends);

		c.onRequestStart("/first");
		// 未调终态，直接第二次 start
		c.onRequestStart("/second");
		c.onRequestSuccess("/second", 200, 10);

		// 第一次（泄漏→ERROR flush）+ 第二次（成功）= 2 次 send
		assertEquals(2, sends.size());
		assertTrue(sends.get(0).get(sends.get(0).size() - 1).contains("\"level\":\"ERROR\""));
		assertTrue(sends.get(1).get(sends.get(1).size() - 1).contains("\"level\":\"DEFAULT\""));
	}

	/** 路径含引号/反斜杠：JSON 转义正确，不破坏信封。 */
	@Test
	public void testJsonEscaping() {
		List<List<String>> sends = new ArrayList<>();
		LangfuseMetricsCollector c = newCollector(sends);
		c.onRequestStart("/v1/chat?q=\"a\\b\"");
		c.onRequestSuccess("/v1/chat?q=\"a\\b\"", 200, 1);
		String trace = sends.get(0).get(0);
		assertTrue(trace.contains("\\\"a\\\\b\\\""));
	}

	/** json 工具：null → 空串字面量；控制字符转义。 */
	@Test
	public void testJsonHelper() {
		assertEquals("\"\"", LangfuseMetricsCollector.json(null));
		assertEquals("\"a\\tb\"", LangfuseMetricsCollector.json("a\tb"));
		assertEquals("\"line1\\nline2\"", LangfuseMetricsCollector.json("line1\nline2"));
	}

	/** 从事件 JSON 中正则抽取第一个捕获组。 */
	private static String extract(String json, String regex) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(json);
		assertTrue("pattern not found: " + regex + " in " + json, m.find());
		return m.group(1);
	}
}
