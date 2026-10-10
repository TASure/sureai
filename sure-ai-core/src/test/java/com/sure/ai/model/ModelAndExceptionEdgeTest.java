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

package com.sure.ai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiBudgetExceededException;
import com.sure.ai.exception.AiRateLimitException;
import com.sure.ai.exception.AiTimeoutException;

/**
 * 模型片段 record、异常类与小型记录的边界访问器测试。
 *
 * @author sureai
 * @since 1.4.0
 */
public class ModelAndExceptionEdgeTest {

	// ==================== MessagePart 片段 ====================

	/** VideoPart 各工厂与 resolvedUrl 分支。 */
	@Test
	public void testVideoPart() {
		VideoPart urlPart = VideoPart.ofUrl("http://v/x.mp4");
		assertEquals("video_url", urlPart.type());
		assertEquals("http://v/x.mp4", urlPart.resolvedUrl());

		VideoPart b64 = VideoPart.ofBase64("AAAA", "video/mp4");
		assertNull(b64.url());
		// base64 分支
		assertTrue(b64.resolvedUrl().startsWith("data:video/mp4;base64,"));

		// 规范构造器直调
		VideoPart raw = new VideoPart("u", "b", "m");
		assertEquals("u", raw.url());
	}

	/** TextPart 紧凑构造器：type 为 null 时 cacheControl 归一为 null。 */
	@Test
	public void testTextPartCompactCtor() {
		TextPart t = new TextPart("hi", null);
		assertEquals("text", t.type());
		assertNull(t.cacheControl());
		TextPart withCache = TextPart.ofWithCache("hi", CacheControl.ephemeral());
		assertEquals("ephemeral", withCache.cacheControl().type());
	}

	/** DocumentPart fileId 空白归一为 null。 */
	@Test
	public void testDocumentPartBlankFileId() {
		DocumentPart dp = DocumentPart.ofFileId("  ");
		assertNull(dp.fileId());
		assertEquals("document", dp.type());
		DocumentPart b64 = DocumentPart.ofBase64("a.pdf", "application/pdf", "QQ==");
		assertEquals("a.pdf", b64.name());
	}

	/** ChatMessage 六参 of 工厂。 */
	@Test
	public void testChatMessageOfSixArgs() {
		ChatMessage m = ChatMessage.of(Role.USER, "hi", null, "Alice", null, null);
		assertEquals("Alice", m.name());
		assertSame(Role.USER, m.role());
	}

	// ==================== 异常类 ====================

	/** AiTimeoutException 全构造器。 */
	@Test
	public void testAiTimeoutException() {
		AiTimeoutException e1 = new AiTimeoutException("msg");
		assertEquals("msg", e1.getMessage());
		AiTimeoutException e2 = new AiTimeoutException("msg2", new RuntimeException("c"));
		assertEquals("msg2", e2.getMessage());
		assertTrue(e2.getCause() instanceof RuntimeException);
	}

	/** AiAuthException 双参构造器。 */
	@Test
	public void testAiAuthException() {
		AiAuthException e = new AiAuthException("bad key", "{\"err\":1}");
		assertEquals(401, e.getHttpStatus());
		assertEquals("{\"err\":1}", e.getRawBody());
	}

	/** AiBudgetExceededException tenantId 访问器。 */
	@Test
	public void testAiBudgetExceeded() {
		AiBudgetExceededException e = new AiBudgetExceededException("t1", "over");
		assertEquals("t1", e.tenantId());
	}

	/** AiRateLimitException retryAfterSeconds 访问器。 */
	@Test
	public void testAiRateLimitException() {
		AiRateLimitException e = new AiRateLimitException("slow", "raw", 30);
		assertEquals(Integer.valueOf(30), e.getRetryAfterSeconds());
		assertEquals(429, e.getHttpStatus());
	}

	/** AiApiException errorCode 访问器。 */
	@Test
	public void testAiApiException() {
		AiApiException e = new AiApiException(400, "invalid_request", "bad", "raw");
		assertEquals(400, e.getHttpStatus());
		assertEquals("invalid_request", e.getErrorCode());
		assertEquals("raw", e.getRawBody());
	}

	// ==================== 小型记录 ====================

	/** ToolCall / ToolFunction 工厂访问器。 */
	@Test
	public void testToolRecords() {
		ToolCall tc = ToolCall.of("id1", "name1", "{\"a\":1}");
		assertEquals("id1", tc.id());
		assertEquals("name1", tc.name());
		assertEquals("{\"a\":1}", tc.argumentsJson());
		ToolFunction fn = ToolFunction.of("n", "d", "{}");
		assertEquals("n", fn.name());
		assertEquals("d", fn.description());
	}

	/** Capability 枚举。 */
	@Test
	public void testCapabilityEnum() {
		assertTrue(com.sure.ai.client.Capability.CHAT.toString().length() > 0);
	}
}
