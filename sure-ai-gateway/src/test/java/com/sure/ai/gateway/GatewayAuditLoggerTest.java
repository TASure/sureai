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
package com.sure.ai.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.Test;

/**
 * {@link GatewayAuditLogger} 单元测试。
 *
 * @author sureai
 * @since 2.7.0
 */
public class GatewayAuditLoggerTest {

	/** 捕获 JUL 输出的测试 Handler。 */
	private static final class CapturingHandler extends Handler {
		private final List<String> messages = new ArrayList<>();

		@Override
		public void publish(LogRecord record) {
			this.messages.add(record.getMessage());
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	}

	/** 新建一个带捕获 Handler 的注入用 logger。 */
	private static GatewayAuditLogger newLogger(CapturingHandler handler) {
		Logger logger = Logger.getLogger("test.gateway.audit." + System.nanoTime());
		logger.setLevel(Level.ALL);
		logger.setUseParentHandlers(false);
		handler.setLevel(Level.ALL);
		logger.addHandler(handler);
		return new GatewayAuditLogger(logger);
	}

	/** 构造一条测试事件，detail 长度超过 200 并以 TAIL 结尾以便断言截断。 */
	private static AuditEvent sampleEvent(String requestId) {
		String detail = "START" + repeat('y', 200) + "TAIL";
		return new AuditEvent(
			requestId, "chat", "gpt", "tenant-1", "openai", "i-9",
			AuditEvent.Status.SUCCESS, 123L, 0.42d, detail, 1700L);
	}

	private static String repeat(char c, int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) {
			sb.append(c);
		}
		return sb.toString();
	}

	/** 单条日志包含 status / tenant / cost / requestId，且 detail 尾部被截断（不落全文）。 */
	@Test
	public void logSingleEventContainsFieldsAndTruncatesDetail() {
		CapturingHandler handler = new CapturingHandler();
		GatewayAuditLogger logger = newLogger(handler);
		logger.log(sampleEvent("req-1"));

		assertEquals(1, handler.messages.size());
		String line = handler.messages.get(0);
		assertTrue(line, line.contains("req-1"));
		assertTrue(line, line.contains("SUCCESS"));
		assertTrue(line, line.contains("tenant-1"));
		assertTrue(line, line.contains("0.42"));
		assertTrue(line, line.contains("START"));
		assertFalse("detail 应被截断到 200 字符，尾部 TAIL 不落日志", line.contains("TAIL"));
	}

	/** event 为 null 时不产生任何日志记录。 */
	@Test
	public void nullEventIsIgnored() {
		CapturingHandler handler = new CapturingHandler();
		GatewayAuditLogger logger = newLogger(handler);
		logger.log((AuditEvent) null);

		assertEquals(0, handler.messages.size());
	}

	/** 批量 log 中每条事件都落一行日志。 */
	@Test
	public void batchLogRecordsEachEvent() {
		CapturingHandler handler = new CapturingHandler();
		GatewayAuditLogger logger = newLogger(handler);
		List<AuditEvent> events = new ArrayList<>();
		events.add(sampleEvent("req-a"));
		events.add(sampleEvent("req-b"));
		logger.log(events);

		assertEquals(2, handler.messages.size());
		assertTrue(handler.messages.get(0), handler.messages.get(0).contains("req-a"));
		assertTrue(handler.messages.get(1), handler.messages.get(1).contains("req-b"));
	}

	/** 自定义 logger 注入生效：输出进入注入的 logger 而非默认 logger。 */
	@Test
	public void injectedLoggerReceivesOutput() {
		CapturingHandler handler = new CapturingHandler();
		GatewayAuditLogger logger = newLogger(handler);
		logger.log(sampleEvent("req-x"));

		assertEquals(1, handler.messages.size());
		assertTrue(handler.messages.get(0), handler.messages.get(0).contains("req-x"));
	}

	/** 默认构造器使用名为 com.sure.ai.gateway.Audit 的 JUL logger，且调用不抛异常。 */
	@Test
	public void defaultConstructorWorks() {
		GatewayAuditLogger logger = new GatewayAuditLogger();
		logger.log(sampleEvent("req-default"));

		assertEquals(GatewayAuditLogger.LOGGER_NAME,
			Logger.getLogger(GatewayAuditLogger.LOGGER_NAME).getName());
	}
}
