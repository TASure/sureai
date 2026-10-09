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

package com.sure.ai.framework.advisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

import org.junit.Test;

/**
 * {@link LoggingAdvisor} 测试（零真实网络）：断言结构化日志被发布且不抛异常。
 *
 * @author sureai
 * @since 2.5.0
 */
public class LoggingAdvisorTest {

	@Test
	public void logsStructuredSummaryAndPassesResponse() {
		Logger log = Logger.getLogger(LoggingAdvisor.class.getName());
		List<LogRecord> records = new java.util.ArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		log.addHandler(handler);
		try {
			ScriptedClient client = new ScriptedClient().then(ScriptedClient.text("你好"));
			ChatRequest req = ChatRequest.builder().model("test-model")
				.messages(List.of(ChatMessage.user("hi"))).build();
			AdvisorContext ctx = new AdvisorContext(req, null, null);
			AdvisorChain chain = new AdvisorChain(List.of(new LoggingAdvisor()),
				c -> client.chat(c.rebuildRequest()));

			ChatResponse resp = chain.execute(ctx);
			assertEquals("你好", resp.firstText());

			assertEquals(1, records.size());
			String msg = records.get(0).getMessage();
			assertTrue("应含 model: " + msg, msg.contains("model=test-model"));
			assertTrue("应含耗时: " + msg, msg.contains("durationMs="));
		} finally {
			log.removeHandler(handler);
		}
	}

	@Test
	public void logsFailureButPropagates() {
		Logger log = Logger.getLogger(LoggingAdvisor.class.getName());
		List<LogRecord> records = new java.util.ArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		log.addHandler(handler);
		try {
			AdvisorChain chain = new AdvisorChain(List.of(new LoggingAdvisor()), c -> {
				throw new RuntimeException("boom");
			});
			ChatRequest req = ChatRequest.builder().model("m")
				.messages(List.of(ChatMessage.user("hi"))).build();
			try {
				chain.execute(new AdvisorContext(req, null, null));
			} catch (RuntimeException e) {
				assertEquals("boom", e.getMessage());
			}
			assertEquals(1, records.size());
		} finally {
			log.removeHandler(handler);
		}
	}
}
