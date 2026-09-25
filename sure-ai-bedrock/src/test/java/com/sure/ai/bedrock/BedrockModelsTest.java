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

package com.sure.ai.bedrock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationTargetException;

import org.junit.Test;

/**
 * {@link BedrockModels} 常量与私有构造器测试。
 *
 * @author sureai
 * @since 1.2.0
 */
public class BedrockModelsTest {

	/** 关键模型常量值。 */
	@Test
	public void testConstants() {
		assertEquals("anthropic.claude-sonnet-4-0:1", BedrockModels.ANTHROPIC_CLAUDE_SONNET_4);
		assertEquals("meta.llama3-70b-instruct-v1:0", BedrockModels.META_LLAMA3_70B);
		assertEquals("amazon.titan-text-express-v1", BedrockModels.AMAZON_TITAN_TEXT_G1);
		assertEquals("amazon.nova-pro-v1:0", BedrockModels.AMAZON_NOVA_PRO);
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateConstructor() throws Exception {
		java.lang.reflect.Constructor<BedrockModels> c =
			BedrockModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		InvocationTargetException ex = assertThrows(InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
	}
}
