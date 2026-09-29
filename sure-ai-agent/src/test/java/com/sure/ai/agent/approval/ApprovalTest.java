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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import org.junit.Test;

/**
 * {@link com.sure.ai.agent.approval} 包单元测试。
 */
public class ApprovalTest {

	private static JsonObject args() {
		return Json.object();
	}

	private static JsonObject argsWithAmount(double amount) {
		JsonObject o = Json.object();
		o.put("amount", amount);
		return o;
	}

	@Test
	public void testAllToolsPolicy() {
		ApprovalPolicy p = AllToolsApprovalPolicy.instance();
		assertTrue(p.requiresApproval("anything", args()));
		assertTrue(p.requiresApproval("get_weather", args()));
	}

	@Test
	public void testNeverPolicy() {
		ApprovalPolicy p = NeverApprovalPolicy.instance();
		assertFalse(p.requiresApproval("payment.transfer", args()));
		assertFalse(p.requiresApproval("get_weather", args()));
	}

	@Test
	public void testToolNamePolicy() {
		ApprovalPolicy p = new ToolNameApprovalPolicy(Set.of("payment.transfer", "delete.file"));
		assertTrue(p.requiresApproval("payment.transfer", args()));
		assertTrue(p.requiresApproval("delete.file", args()));
		assertFalse(p.requiresApproval("get_weather", args()));
	}

	@Test
	public void testToolNamePolicyVarargs() {
		ApprovalPolicy p = ToolNameApprovalPolicy.of("exec.shell");
		assertTrue(p.requiresApproval("exec.shell", args()));
		assertFalse(p.requiresApproval("other", args()));
	}

	@Test
	public void testHighRiskPolicyByNamePrefix() {
		HighRiskApprovalPolicy p = new HighRiskApprovalPolicy();
		assertTrue(p.requiresApproval("payment.transfer", args()));
		assertTrue(p.requiresApproval("delete_user", args()));
		assertTrue(p.requiresApproval("exec_cmd", args()));
		assertFalse(p.requiresApproval("get_weather", args()));
	}

	@Test
	public void testHighRiskPolicyByAmountArg() {
		HighRiskApprovalPolicy p = new HighRiskApprovalPolicy();
		// 工具名不高危，但参数含正值金额
		assertTrue(p.requiresApproval("do_something", argsWithAmount(100)));
		// 金额为 0 不触发
		assertFalse(p.requiresApproval("do_something", argsWithAmount(0)));
		// 普通参数不触发
		assertFalse(p.requiresApproval("do_something", args()));
	}

	@Test
	public void testApprovalGateShortCircuit() {
		AtomicInteger calls = new AtomicInteger();
		ApprovalHandler counting = new ApprovalHandler() {
			@Override
			public ApprovalDecision request(ApprovalRequest request) {
				calls.incrementAndGet();
				return ApprovalDecision.approved();
			}

			@Override
			public String name() {
				return "counting";
			}
		};
		ApprovalGate gate = new ApprovalGate(NeverApprovalPolicy.instance(), counting);
		// 策略不要求审批：handler 不应被调用，直接 APPROVED
		ApprovalDecision d = gate.request(ApprovalRequest.of("agent", "get_weather", args(),
			"查天气", false));
		assertEquals(ApprovalStatus.APPROVED, d.status());
		assertEquals(0, calls.get());
	}

	@Test
	public void testApprovalGateApproved() {
		ApprovalGate gate = new ApprovalGate(AllToolsApprovalPolicy.instance(),
			AutoApprovalHandler.instance());
		assertTrue(gate.requiresApproval("any", args()));
		ApprovalDecision d = gate.request(ApprovalRequest.of("agent", "any", args(), "动作", false));
		assertEquals(ApprovalStatus.APPROVED, d.status());
	}

	@Test
	public void testApprovalGateRejectedDelegates() {
		ApprovalGate gate = new ApprovalGate(AllToolsApprovalPolicy.instance(),
			AutoRejectHandler.withReason("余额不足"));
		ApprovalDecision d = gate.request(ApprovalRequest.of("agent", "pay", args(), "付钱", true));
		assertEquals(ApprovalStatus.REJECTED, d.status());
		assertEquals("余额不足", d.reason());
	}

	@Test
	public void testTimeoutApprovalHandler() {
		// 被包装 handler 永远阻塞；50ms 超时应返回 TIMEOUT
		ApprovalHandler blocking = new ApprovalHandler() {
			@Override
			public ApprovalDecision request(ApprovalRequest request) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return ApprovalDecision.approved();
			}

			@Override
			public String name() {
				return "blocking";
			}
		};
		TimeoutApprovalHandler timeout = new TimeoutApprovalHandler(blocking,
			Duration.ofMillis(100));
		long start = System.currentTimeMillis();
		ApprovalDecision d = timeout.request(
			ApprovalRequest.of("agent", "pay", args(), "付钱", true));
		long elapsed = System.currentTimeMillis() - start;
		assertEquals(ApprovalStatus.TIMEOUT, d.status());
		assertTrue("应在合理时间内超时, actual=" + elapsed, elapsed < 1500);
	}

	@Test
	public void testApprovalRequestDefaults() {
		ApprovalRequest r = ApprovalRequest.of("agent-1", "get_weather", args(), "查天气", false);
		assertTrue(r.requestId() != null && !r.requestId().isBlank());
		assertEquals("agent-1", r.agentId());
		assertEquals("get_weather", r.toolName());
		assertTrue(r.requestedAtEpochMs() > 0);
	}

	@Test
	public void testApprovalDecisionFactories() {
		assertEquals(ApprovalStatus.APPROVED, ApprovalDecision.approved().status());
		assertEquals("no", ApprovalDecision.rejected("no").reason());
		assertEquals(ApprovalStatus.TIMEOUT, ApprovalDecision.timeout().status());
	}
}
