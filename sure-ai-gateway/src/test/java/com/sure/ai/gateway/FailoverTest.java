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
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * 故障转移行为测试（零网络，全部走 FakeClient）。
 *
 * <p>为避免注册表迭代顺序不确定，测试策略按平台名排序后取第一个；并把"失败方"注册在
 * 字典序更小的平台名下，保证它总是第一个被选中。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public class FailoverTest {

	/** 确定性策略：按平台名排序后选第一个健康候选。 */
	private static final class FirstStrategy implements RoutingStrategy {
		@Override
		public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
			return candidates.stream()
				.sorted((x, y) -> (x.platform() + x.instanceId())
					.compareTo(y.platform() + y.instanceId()))
				.findFirst()
				.orElse(null);
		}
	}

	/** 记录事件的监听器。 */
	private static final class RecordingListener implements FailoverListener {
		int failovers;
		String toPlatform;
		boolean exhausted;

		@Override
		public void onFailover(String fromPlatform, String fromInstanceId,
				String toPlatform, String toInstanceId, Exception cause, int attempt) {
			this.failovers++;
			this.toPlatform = toPlatform;
		}

		@Override
		public void onExhausted(List<ClientCandidate> tried, Exception lastCause) {
			this.exhausted = true;
		}
	}

	private static ChatRequest req() {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
	}

	/** 首个客户端成功：不触发转移。 */
	@Test
	public void firstClientSucceedsNoFailover() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A");
		registry.register("a", a);
		RecordingListener listener = new RecordingListener();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
				FailoverConfig.builder().listener(listener).build());

		ChatResponse resp = gw.chat(req());
		assertEquals("A:ok", resp.firstText());
		assertEquals(1, a.chatCalls);
		assertEquals(0, listener.failovers);
	}

	/** 首个超时后转移到第二个成功：断言 onFailover 被调用且结果来自第二个。 */
	@Test
	public void failoverOnTimeout() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("timeout"));
		FakeClient z = new FakeClient("Z");
		registry.register("a", a);
		registry.register("z", z);
		RecordingListener listener = new RecordingListener();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
				FailoverConfig.builder().listener(listener).build());

		ChatResponse resp = gw.chat(req());
		assertEquals("Z:ok", resp.firstText());
		assertEquals(1, a.chatCalls);
		assertEquals(1, z.chatCalls);
		assertEquals(1, listener.failovers);
		assertEquals("z", listener.toPlatform);
	}

	/** 4xx 鉴权错误不转移，直接抛原异常。 */
	@Test
	public void authErrorNotFailedOver() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiAuthException("bad key", "{}"));
		FakeClient z = new FakeClient("Z");
		registry.register("a", a);
		registry.register("z", z);
		RecordingListener listener = new RecordingListener();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
				FailoverConfig.builder().listener(listener).build());

		try {
			gw.chat(req());
			fail("expected AiAuthException");
		} catch (AiAuthException expected) {
			// expected
		}
		assertEquals(1, a.chatCalls);
		assertEquals(0, z.chatCalls);
		assertEquals(0, listener.failovers);
		assertFalse(listener.exhausted);
	}

	/** 全部候选失败：达到 maxAttempts 后抛 AiException，onExhausted 被调用。 */
	@Test
	public void allClientsFailExhausted() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t1"));
		FakeClient z = new FakeClient("Z").throwOn(new AiTimeoutException("t2"));
		registry.register("a", a);
		registry.register("z", z);
		RecordingListener listener = new RecordingListener();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
				FailoverConfig.builder().maxAttempts(2).listener(listener).build());

		try {
			gw.chat(req());
			fail("expected AiException");
		} catch (AiException expected) {
			// expected
		}
		assertEquals(1, a.chatCalls);
		assertEquals(1, z.chatCalls);
		assertTrue(listener.exhausted);
	}

	/** 失败的客户端在冷却期内不再被选中：连续第二次调用仍走健康的第二个。 */
	@Test
	public void unhealthyClientSkippedOnSubsequentCall() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient z = new FakeClient("Z");
		registry.register("a", a);
		registry.register("z", z);
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
				FailoverConfig.builder().unhealthyCooldownMs(60_000L).build());

		gw.chat(req());
		gw.chat(req());

		assertEquals(1, a.chatCalls);
		assertEquals(2, z.chatCalls);
	}
}
