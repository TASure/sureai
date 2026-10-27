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

package com.sure.ai.agent;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import com.sure.ai.agent.checkpoint.AgentCheckpointer;
import com.sure.ai.agent.checkpoint.CheckpointSerializer;
import com.sure.ai.agent.event.AgentEventSseWriter;
import org.junit.Test;

/**
 * 工具类私有构造器防御性分支测试。
 *
 * <p>通过反射调用私有构造器，验证其按约定抛出 {@link AssertionError}，
 * 同时覆盖构造器内不可实例化防御行。</p>
 */
public class PrivateConstructorTest {

	private static void assertUninstantiable(Class<?> clazz) throws Exception {
		Constructor<?> ctor = clazz.getDeclaredConstructor();
		ctor.setAccessible(true);
		InvocationTargetException ex = assertThrows(InvocationTargetException.class,
				ctor::newInstance);
		Throwable cause = ex.getTargetException();
		assertTrue(cause instanceof AssertionError);
	}

	@Test
	public void testUtilityClassesCannotBeInstantiated() throws Exception {
		assertUninstantiable(AgentUtil.class);
		assertUninstantiable(AgentCheckpointer.class);
		assertUninstantiable(CheckpointSerializer.class);
		assertUninstantiable(AgentEventSseWriter.class);
	}
}
