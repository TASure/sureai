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

package com.sure.ai.agent.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import org.junit.Test;

/**
 * {@link SkillTool} 工具化适配器测试：声明、注册、执行与按需加载计数。
 */
public class SkillToolTest {

	/** 计数型假内容：验证正文/资源按需读取。 */
	private static final class CountingContent implements SkillContent {
		private final AtomicInteger bodyCalls = new AtomicInteger();
		private final AtomicInteger resourceCalls = new AtomicInteger();

		@Override
		public String readBody() {
			this.bodyCalls.incrementAndGet();
			return "SKILL BODY";
		}

		@Override
		public String readResource(String relativePath) {
			this.resourceCalls.incrementAndGet();
			return "RES:" + relativePath;
		}

		@Override
		public String origin() {
			return "fake";
		}
	}

	private static Skill fakeSkill(CountingContent content) {
		SkillManifest manifest = new SkillManifest("fake-skill",
				"A fake skill description. Use when faking.", null, null,
				List.of(), Map.of(), Map.of());
		return new Skill(manifest, content);
	}

	@Test
	public void testToolFunctionDeclaration() {
		Skill skill = fakeSkill(new CountingContent());
		var fn = SkillTool.toToolFunction(skill);
		assertEquals("fake-skill", fn.name());
		assertEquals("A fake skill description. Use when faking.", fn.description());
		assertNotNull(fn.parameters());
		assertTrue(fn.parameters().contains("resource"));
	}

	@Test
	public void testRegisterAndExecuteBody() throws Exception {
		CountingContent content = new CountingContent();
		Skill skill = fakeSkill(content);
		ToolRegistry registry = new ToolRegistry();
		SkillTool.register(skill, registry);

		assertEquals(1, registry.size());
		assertTrue(registry.getFunction("fake-skill").isPresent());
		assertTrue(registry.getHandler("fake-skill").isPresent());

		String result = registry.getHandler("fake-skill").get().execute(new JsonObject());
		assertEquals("SKILL BODY", result);
		assertEquals(1, content.bodyCalls.get());
		assertEquals(0, content.resourceCalls.get());
	}

	@Test
	public void testExecuteResourceArgument() throws Exception {
		CountingContent content = new CountingContent();
		Skill skill = fakeSkill(content);
		ToolRegistry registry = new ToolRegistry();
		SkillTool.register(skill, registry);

		JsonObject args = (JsonObject) Json.parse("{\"resource\":\"references/guide.md\"}");
		String result = registry.getHandler("fake-skill").get().execute(args);
		assertEquals("RES:references/guide.md", result);
		assertEquals(0, content.bodyCalls.get());
		assertEquals(1, content.resourceCalls.get());
	}

	@Test
	public void testBlankResourceFallsBackToBody() throws Exception {
		CountingContent content = new CountingContent();
		Skill skill = fakeSkill(content);
		String out = SkillTool.toHandler(skill)
				.execute((JsonObject) Json.parse("{\"resource\":\"   \"}"));
		assertEquals("SKILL BODY", out);
	}

	@Test
	public void testNullArgumentsReturnsBody() throws Exception {
		CountingContent content = new CountingContent();
		Skill skill = fakeSkill(content);
		assertEquals("SKILL BODY", SkillTool.toHandler(skill).execute(null));
	}

	@Test
	public void testRegisterAll() {
		Skill a = fakeSkill(new CountingContent());
		Skill b = new Skill(
				new SkillManifest("second-skill", "Second.", null, null, List.of(), Map.of(), Map.of()),
				new CountingContent());
		ToolRegistry registry = new ToolRegistry();
		SkillTool.registerAll(List.of(a, b), registry);
		assertEquals(2, registry.size());
		assertTrue(registry.getFunction("second-skill").isPresent());
	}

	@Test
	public void testResourceErrorPropagates() {
		SkillContent content = new SkillContent() {
			@Override
			public String readBody() {
				return "BODY";
			}

			@Override
			public String readResource(String relativePath) {
				throw new SkillResourceException("资源不存在: nope.md");
			}

			@Override
			public String origin() {
				return "fake";
			}
		};
		Skill skill = new Skill(
				new SkillManifest("local-skill", "d", null, null, List.of(), Map.of(), Map.of()),
				content);
		ToolRegistry registry = new ToolRegistry();
		SkillTool.register(skill, registry);
		assertThrows(SkillResourceException.class,
				() -> registry.getHandler("local-skill").get().execute(
						(JsonObject) Json.parse("{\"resource\":\"nope.md\"}")));
	}

	@Test
	public void testNullArgsRejected() {
		assertThrows(IllegalArgumentException.class, () -> SkillTool.toToolFunction(null));
		assertThrows(IllegalArgumentException.class, () -> SkillTool.toHandler(null));
		assertThrows(IllegalArgumentException.class, () -> SkillTool.register(null, new ToolRegistry()));
		assertThrows(IllegalArgumentException.class,
				() -> SkillTool.register(fakeSkill(new CountingContent()), null));
	}
}
