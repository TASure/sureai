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

package com.sure.ai.agent.approval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.sure.ai.internal.json.Json;

/**
 * {@link ConsoleApprovalHandler} 单元测试。
 *
 * <p>通过 {@link System#setIn} 注入内存输入流模拟控制台 y/n，零真实交互。</p>
 */
public class ConsoleApprovalHandlerTest {

	private ApprovalRequest request() {
		return ApprovalRequest.of("agent-1", "pay", Json.object(), "付钱", true);
	}

	@Test
	public void testApprovesOnYes() {
		System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
		ConsoleApprovalHandler handler = new ConsoleApprovalHandler();
		ApprovalDecision d = handler.request(request());
		assertEquals(ApprovalStatus.APPROVED, d.status());
		assertEquals("console", handler.name());
	}

	@Test
	public void testRejectsOnOtherInput() {
		System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));
		ConsoleApprovalHandler handler = new ConsoleApprovalHandler();
		ApprovalDecision d = handler.request(request());
		assertEquals(ApprovalStatus.REJECTED, d.status());
		assertTrue(d.reason().contains("console"));
	}

	@Test
	public void testApprovesOnChineseYes() {
		System.setIn(new ByteArrayInputStream("是\n".getBytes(StandardCharsets.UTF_8)));
		ConsoleApprovalHandler handler = new ConsoleApprovalHandler();
		assertEquals(ApprovalStatus.APPROVED, handler.request(request()).status());
	}
}
