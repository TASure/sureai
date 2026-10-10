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

package com.sure.ai.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link McpSubscriptions} 补测：覆盖 params 为空、accepted 为 null、id 为 null 的防御分支与私有构造器。
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpSubscriptionsTest {

	/** params 为空或无 notifications 字段时返回空对象。 */
	@Test
	public void acceptedWithNullOrMissingNotifications() {
		assertEquals(0, McpSubscriptions.accepted(null).size());
		assertEquals(0, McpSubscriptions.accepted(Json.object()).size());
		JsonObject arrParams = Json.object();
		arrParams.set("notifications", Json.array());
		assertEquals(0, McpSubscriptions.accepted(arrParams).size());
	}

	/** accepted 保留 true 布尔键，丢弃 false 与未知键。 */
	@Test
	public void acceptedKeepsTrueBoolKeys() {
		JsonObject filter = Json.object();
		filter.put(McpSubscriptions.TOOLS_LIST_CHANGED, Boolean.TRUE);
		filter.put(McpSubscriptions.PROMPTS_LIST_CHANGED, Boolean.FALSE);
		JsonObject params = Json.object();
		params.set("notifications", filter);
		JsonObject out = McpSubscriptions.accepted(params);
		assertTrue(out.getBoolean(McpSubscriptions.TOOLS_LIST_CHANGED));
		assertFalse(out.has(McpSubscriptions.PROMPTS_LIST_CHANGED));
	}

	/** resourceSubscriptions 为数组时透传，非数组时丢弃。 */
	@Test
	public void acceptedResourceSubscriptionsArrayOnly() {
		JsonObject ok = Json.object();
		com.sure.ai.internal.json.JsonArray uris = Json.array();
		uris.add("file:///a");
		ok.set(McpSubscriptions.RESOURCE_SUBSCRIPTIONS, uris);
		JsonObject params = Json.object();
		params.set("notifications", ok);
		assertEquals(1, McpSubscriptions.accepted(params).getJsonArray(McpSubscriptions.RESOURCE_SUBSCRIPTIONS).size());

		JsonObject bad = Json.object();
		bad.put(McpSubscriptions.RESOURCE_SUBSCRIPTIONS, "not-an-array");
		JsonObject badParams = Json.object();
		badParams.set("notifications", bad);
		assertFalse(McpSubscriptions.accepted(badParams).has(McpSubscriptions.RESOURCE_SUBSCRIPTIONS));
	}

	/** ackNotification 处理 null id 与 null accepted。 */
	@Test
	public void ackNotificationNullArgs() {
		JsonObject n = McpSubscriptions.ackNotification(null, null);
		assertEquals("2.0", n.getString("jsonrpc"));
		assertEquals(McpSubscriptions.ACK_METHOD, n.getString("method"));
		assertTrue(n.getJsonObject("params").getJsonObject("_meta")
			.get(McpSubscriptions.META_SUBSCRIPTION_ID).isNull());
		assertEquals(0, n.getJsonObject("params").getJsonObject("notifications").size());
	}

	/** 私有构造器应抛 AssertionError。 */
	@Test
	public void privateConstructorThrows() {
		try {
			var ctor = McpSubscriptions.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			ctor.newInstance();
			assertTrue("应抛 AssertionError", false);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			assertEquals(AssertionError.class, ex.getCause().getClass());
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}
}
