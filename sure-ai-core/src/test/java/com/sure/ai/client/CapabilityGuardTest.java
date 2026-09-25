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

package com.sure.ai.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ModerationRequest;

/**
 * 能力声明 / 快速失败守卫（1.4.0 P2-6）单元测试。
 *
 * <p>不启动 HTTP 服务：未声明能力的方法在 {@code guard} 处即抛出，先于任何网络调用。
 * 通过只声明部分能力的探测子类验证守卫的双向行为。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public class CapabilityGuardTest {

	/**
	 * 只声明对话 + 向量的受限兼容客户端，用于触发快速失败。
	 */
	static final class LimitedCompatClient extends OpenAiCompatClient {

		LimitedCompatClient(AiConfig config) {
			super(config);
		}

		@Override
		public String name() {
			return "limited";
		}

		@Override
		protected Set<Capability> capabilities() {
			return Set.of(Capability.CHAT, Capability.CHAT_STREAM, Capability.EMBED);
		}

		/** 暴露 guard 以便直接断言双向行为。 */
		void check(Capability c) {
			guard(c);
		}

		/** 暴露 capabilities() 供断言。 */
		Set<Capability> exposedCaps() {
			return capabilities();
		}
	}

	/**
	 * 不覆写 capabilities() 的探测客户端，用于验证基类默认声明（全量能力）。
	 */
	static final class FullCompatClient extends OpenAiCompatClient {

		FullCompatClient(AiConfig config) {
			super(config);
		}

		@Override
		public String name() {
			return "base";
		}

		/** 暴露 capabilities() 供断言。 */
		Set<Capability> exposedCaps() {
			return capabilities();
		}
	}

	/** guard 对未声明能力抛 AiException，且消息含平台名与能力名。 */
	@Test
	public void guardThrowsWhenUnsupported() {
		LimitedCompatClient client =
			new LimitedCompatClient(AiConfig.of("k"));
		AiException ex = assertThrows(AiException.class, () -> client.check(Capability.IMAGE));
		assertEquals("limited does not support IMAGE capability", ex.getMessage());
		client.close();
	}

	/** guard 对已声明能力为空操作，不抛异常。 */
	@Test
	public void guardNoOpWhenSupported() {
		LimitedCompatClient client =
			new LimitedCompatClient(AiConfig.of("k"));
		// EMBED/CHAT 已声明：不应抛 AiException（无网络、无 IO）。
		client.check(Capability.EMBED);
		client.check(Capability.CHAT);
		client.close();
	}

	/**
	 * 未声明能力的 public 方法在发请求前快速失败：无 HTTP 服务在场仍抛 AiException，
	 * 证明 guard 先于网络调用。
	 */
	@Test
	public void unsupportedMethodFailsFastBeforeNetwork() {
		LimitedCompatClient client =
			new LimitedCompatClient(AiConfig.of("k"));
		AiException ex = assertThrows(AiException.class,
			() -> client.moderate(ModerationRequest.of("dirty input")));
		assertTrue(ex.getMessage().contains("limited"));
		assertTrue(ex.getMessage().contains("MODERATION"));

		AiException ex2 = assertThrows(AiException.class,
			() -> client.generate(ImageRequest.of("model", "a cat")));
		assertTrue(ex2.getMessage().contains("IMAGE"));
		client.close();
	}

	/** 兼容引擎基类声明全量能力（安全默认，未审计子类行为不变）。 */
	@Test
	public void baseDeclaresFullCapabilitySet() {
		FullCompatClient base = new FullCompatClient(AiConfig.of("k"));
		Set<Capability> caps = base.exposedCaps();
		assertTrue(caps.contains(Capability.EMBED));
		assertTrue(caps.contains(Capability.IMAGE));
		assertTrue(caps.contains(Capability.VIDEO));
		assertTrue(caps.contains(Capability.MODERATION));
		assertTrue(caps.contains(Capability.FINETUNE));
		assertTrue(caps.contains(Capability.TTS));
		assertTrue(caps.contains(Capability.STT));
		base.close();
	}

	/** capabilities() 声明正确性：支持的在集合中，不支持的不在。 */
	@Test
	public void declaredCapabilitiesCorrectness() {
		LimitedCompatClient client =
			new LimitedCompatClient(AiConfig.of("k"));
		Set<Capability> caps = client.exposedCaps();
		assertTrue(caps.contains(Capability.CHAT));
		assertTrue(caps.contains(Capability.CHAT_STREAM));
		assertTrue(caps.contains(Capability.EMBED));
		assertFalse(caps.contains(Capability.IMAGE));
		assertFalse(caps.contains(Capability.VIDEO));
		assertFalse(caps.contains(Capability.MODERATION));
		assertFalse(caps.contains(Capability.FINETUNE));
		client.close();
	}
}
