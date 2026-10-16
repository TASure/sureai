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
package com.sure.ai.rag;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.junit.Test;

import com.sure.ai.rag.store.mongodb.Bson;
import com.sure.ai.rag.store.resp.RespCodec;

/**
 * 工具类私有构造器守卫补覆盖：反射调用私有构造器，断言抛 {@link AssertionError}。
 *
 * @author sureai
 * @since 2.6.0
 */
public class PrivateCtorExtraTest {

	private static void assertPrivateCtorThrows(Class<?> clazz) throws Exception {
		Constructor<?> c = clazz.getDeclaredConstructor();
		c.setAccessible(true);
		try {
			c.newInstance();
			fail(clazz.getSimpleName() + " 私有构造器应抛 AssertionError");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof AssertionError);
		}
	}

	/** RagUtil 私有构造器。 */
	@Test
	public void testRagUtilCtor() throws Exception {
		assertPrivateCtorThrows(RagUtil.class);
	}

	/** RespCodec 私有构造器。 */
	@Test
	public void testRespCodecCtor() throws Exception {
		assertPrivateCtorThrows(RespCodec.class);
	}

	/** Bson 私有构造器。 */
	@Test
	public void testBsonCtor() throws Exception {
		assertPrivateCtorThrows(Bson.class);
	}
}
