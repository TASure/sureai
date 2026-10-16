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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import org.junit.Test;

/**
 * {@link com.sure.ai.agent.approval} 包边界补充测试。
 *
 * <p>覆盖审批决定紧凑构造校验、门面名称暴露、审批请求缺省 id 与工具名校验、
 * 自动/拒绝/超时处理器名称与异常传播。</p>
 */
public class ApprovalEdgeTest {

	private static JsonObject args() {
		return Json.object();
	}

	@Test
	public void testDecisionRejectsNullStatus() {
		assertThrows(IllegalArgumentException.class,
				() -> new ApprovalDecision(null, "r", 1L));
	}

	@Test
	public void testDecisionNormalizesNonPositiveTimestamp() {
		ApprovalDecision d = new ApprovalDecision(ApprovalStatus.APPROVED, "r", 0L);
		assertTrue(d.decidedAtEpochMs() > 0);
	}

	@Test
	public void testGateExposesPolicyAndHandlerNames() {
		ApprovalGate gate = new ApprovalGate(AllToolsApprovalPolicy.instance(),
				AutoApprovalHandler.instance());
		assertEquals("AllToolsApprovalPolicy", gate.policyName());
		assertEquals("auto", gate.handlerName());
	}

	@Test
	public void testRequestDefaultsIdAndRejectsBlankTool() {
		ApprovalRequest r = ApprovalRequest.of("agent", "pay", args(), "付钱", true);
		assertNotNull(r.requestId());
		assertThrows(IllegalArgumentException.class,
				() -> ApprovalRequest.of("agent", " ", args(), "x", false));
	}

	@Test
	public void testHandlerNames() {
		assertEquals("auto", AutoApprovalHandler.instance().name());
		assertEquals("auto-reject", AutoRejectHandler.withReason("no").name());
		TimeoutApprovalHandler timeout = new TimeoutApprovalHandler(
				AutoApprovalHandler.instance(), Duration.ofMillis(100));
		assertTrue(timeout.name().startsWith("timeout("));
	}

	@Test
	public void testTimeoutHandlerPropagatesDelegateFailure() {
		ApprovalHandler failing = new ApprovalHandler() {
			@Override
			public ApprovalDecision request(ApprovalRequest request) {
				throw new IllegalStateException("delegate-boom");
			}

			@Override
			public String name() {
				return "failing";
			}
		};
		TimeoutApprovalHandler timeout = new TimeoutApprovalHandler(failing,
				Duration.ofMillis(1000));
		assertThrows(IllegalStateException.class,
				() -> timeout.request(ApprovalRequest.of("a", "pay", args(), "x", true)));
	}

	@Test
	public void testTimeoutHandlerReturnsDelegateResult() {
		// delegate 立即返回 → future.get 正常返回（非超时分支）
		TimeoutApprovalHandler timeout = new TimeoutApprovalHandler(
				AutoApprovalHandler.instance(), Duration.ofMillis(1000));
		ApprovalDecision d = timeout.request(
				ApprovalRequest.of("a", "pay", args(), "x", true));
		assertEquals(ApprovalStatus.APPROVED, d.status());
	}

	@Test
	public void testTimeoutHandlerInterruptedWhileWaiting() throws Exception {
		// delegate 永远阻塞；确认其已启动后，另一线程中断当前等待中的主线程 →
		// future.get 抛 InterruptedException → 返回 TIMEOUT 决定。
		// 用 CountDownLatch 同步 delegate 启动 + 300ms 缓冲余量，消除跨 OS 调度时序差异。
		java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
		ApprovalHandler blocking = new ApprovalHandler() {
			@Override
			public ApprovalDecision request(ApprovalRequest request) {
				started.countDown();
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return ApprovalDecision.approved("x");
			}

			@Override
			public String name() {
				return "blocking";
			}
		};
		TimeoutApprovalHandler timeout = new TimeoutApprovalHandler(blocking,
				Duration.ofMillis(5000));
		Thread self = Thread.currentThread();
		Thread killer = new Thread(() -> {
			try {
				// 等 delegate 真正启动（= future 已提交、主线程即将进入 get）
				if (!started.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
					return;
				}
				// 缓冲余量：确保主线程已进入 future.get 的阻塞等待
				Thread.sleep(300);
				self.interrupt();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		killer.setDaemon(true);
		killer.start();
		try {
			ApprovalDecision d = timeout.request(
					ApprovalRequest.of("a", "pay", args(), "x", true));
			assertEquals(ApprovalStatus.TIMEOUT, d.status());
		} finally {
			killer.interrupt();
			// 清除当前线程残留的中断标志：TimeoutApprovalHandler 捕获
			// InterruptedException 时会 Thread.currentThread().interrupt() 重新置位，
			// 残留标志会使 JUnit 同线程后续测试的 future.get 立即抛 InterruptedException，
			// 污染断言（如 testTimeoutHandlerPropagatesDelegateFailure 的 ExecutionException 分支）。
			Thread.interrupted();
		}
	}
}
