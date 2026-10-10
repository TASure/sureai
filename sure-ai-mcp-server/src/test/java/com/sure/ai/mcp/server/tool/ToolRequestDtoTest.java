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
package com.sure.ai.mcp.server.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

/**
 * {@code tool} 包入参 record 的构造器与访问器测试：仅为覆盖 record 隐式构造器。
 *
 * @author sureai
 * @since 2.6.0
 */
public class ToolRequestDtoTest {

	/** ChatToolRequest 全参构造与访问器。 */
	@Test
	public void chatToolRequestAccessors() {
		ChatToolRequest.Message m = new ChatToolRequest.Message("user", "hi");
		ChatToolRequest req = new ChatToolRequest("openai", "gpt-x", "hi",
			List.of(m), 0.7, 100);
		assertEquals("openai", req.platform());
		assertEquals("gpt-x", req.model());
		assertEquals("hi", req.prompt());
		assertEquals(1, req.messages().size());
		assertEquals("user", req.messages().get(0).role());
		assertEquals(0.7, req.temperature(), 0.001);
		assertEquals(Integer.valueOf(100), req.maxTokens());
		assertEquals("hi", m.content());
	}

	/** EmbedToolRequest 全参构造与访问器。 */
	@Test
	public void embedToolRequestAccessors() {
		EmbedToolRequest req = new EmbedToolRequest("openai", "emb", List.of("a", "b"), "single");
		assertEquals("openai", req.platform());
		assertEquals("emb", req.model());
		assertEquals(2, req.input().size());
		assertEquals("single", req.text());
	}

	/** ImageToolRequest 全参构造与访问器。 */
	@Test
	public void imageToolRequestAccessors() {
		ImageToolRequest req = new ImageToolRequest("openai", "dall", "cat", "1024x1024");
		assertEquals("openai", req.platform());
		assertEquals("dall", req.model());
		assertEquals("cat", req.prompt());
		assertEquals("1024x1024", req.size());
	}

	/** 全 null 构造不抛异常。 */
	@Test
	public void nullFieldsAccepted() {
		assertNull(new ChatToolRequest(null, null, null, null, null, null).model());
		assertNull(new EmbedToolRequest(null, null, null, null).model());
		assertNull(new ImageToolRequest(null, null, null, null).model());
	}
}
