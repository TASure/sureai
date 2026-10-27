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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.server.skill.McpSkillInfo;
import com.sure.ai.mcp.server.skill.McpSkillResource;
import com.sure.ai.mcp.server.skill.McpSkillSource;

/**
 * Skills over MCP 扩展（{@code io.modelcontextprotocol/skills}，SEP-2640 Final）测试：
 * discover 能力声明、skills/list 分页、skills/get、resources/read（skill://）、
 * resources/directory/read 与错误码。
 *
 * @author sureai
 * @since 2.9.0
 */
public class McpSkillsOverMcpTest {

	/** 内存技能来源（测试用）。 */
	static class InMemorySkillSource implements McpSkillSource {

		private final Map<String, McpSkillInfo> infos = new LinkedHashMap<>();
		private final Map<String, String> resources = new LinkedHashMap<>();
		private boolean directoryRead;

		InMemorySkillSource add(String name, String body, int resourceCount) {
			String root = McpServer.SKILL_URI_SCHEME + name;
			JsonObject fm = Json.object();
			fm.put("name", name);
			fm.put("description", "技能 " + name);
			List<McpSkillResource> list = new ArrayList<>();
			String skillMd = root + "/SKILL.md";
			list.add(new McpSkillResource(skillMd, "sha256:abc" + name, body.length()));
			this.resources.put(skillMd, body);
			for (int i = 0; i < resourceCount; i++) {
				String uri = root + "/references/r" + i + ".md";
				list.add(new McpSkillResource(uri, "sha256:r" + i, 10 + i));
				this.resources.put(uri, "参考" + i);
			}
			this.infos.put(root, McpSkillInfo.of(root, fm, list));
			return this;
		}

		InMemorySkillSource addDynamic(String name) {
			String root = McpServer.SKILL_URI_SCHEME + name;
			JsonObject fm = Json.object();
			fm.put("name", name);
			fm.put("description", "动态技能");
			this.infos.put(root, McpSkillInfo.dynamic(root, fm));
			this.resources.put(root + "/SKILL.md", "动态正文");
			return this;
		}

		InMemorySkillSource directoryRead(boolean enabled) {
			this.directoryRead = enabled;
			return this;
		}

		@Override
		public List<McpSkillInfo> listSkills() {
			return new ArrayList<>(this.infos.values());
		}

		@Override
		public McpSkillInfo getSkill(String uri) {
			return this.infos.get(uri);
		}

		@Override
		public String readResource(String uri) {
			return this.resources.get(uri);
		}

		@Override
		public boolean directoryRead() {
			return this.directoryRead;
		}

		@Override
		public List<McpSkillResource> listDirectory(String uri) {
			McpSkillInfo info = this.infos.get(uri);
			if (info == null) {
				return null;
			}
			return info.resources();
		}
	}

	private static String req(long id, String method, JsonObject params) {
		JsonObject o = Json.object();
		o.put("jsonrpc", "2.0");
		o.put("id", id);
		o.put("method", method);
		if (params != null) {
			o.set("params", params);
		}
		return Json.stringify(o);
	}

	private static JsonObject resultOf(String response) {
		return Json.parse(response).getAsJsonObject().getJsonObject("result");
	}

	private static long errorCode(String response) {
		return Json.parse(response).getAsJsonObject().getJsonObject("error").getInt("code");
	}

	private static JsonObject statelessParams() {
		JsonObject meta = Json.object();
		meta.put(McpServer.META_PROTOCOL_VERSION, McpServer.PROTOCOL_VERSION_STATELESS);
		JsonObject params = Json.object();
		params.set("_meta", meta);
		return params;
	}

	/** 未注册技能来源时 discover 不声明扩展。 */
	@Test
	public void discoverWithoutSkillsOmitsExtension() {
		McpServer server = new McpServer();
		JsonObject result = resultOf(server.dispatch(req(1, "server/discover", null)));
		JsonObject caps = result.getJsonObject("capabilities");
		assertFalse(caps.has("resources"));
		assertFalse(caps.has("extensions"));
	}

	/** 注册来源后 discover 声明 resources 与 skills 扩展（directoryRead 开关）。 */
	@Test
	public void discoverDeclaresSkillsExtension() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("a", "正文", 1).directoryRead(true));
		JsonObject result = resultOf(server.dispatch(req(2, "server/discover", null)));
		JsonObject caps = result.getJsonObject("capabilities");
		assertTrue(caps.has("resources"));
		JsonObject skills = caps.getJsonObject("extensions")
			.getJsonObject(McpServer.EXTENSION_SKILLS);
		assertTrue(skills.getBoolean("directoryRead"));
	}

	/** 未注册来源时 skills/list 为 -32601。 */
	@Test
	public void skillsListWithoutSourceIsMethodNotFound() {
		McpServer server = new McpServer();
		assertEquals(-32601, errorCode(server.dispatch(req(3, "skills/list", null))));
	}

	/** skills/list 返回静态资源清单与动态技能。 */
	@Test
	public void skillsListReturnsEntries() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("pdf", "PDF 正文", 2).addDynamic("big"));
		JsonObject result = resultOf(server.dispatch(req(4, "skills/list", statelessParams())));
		assertEquals("complete", result.getString("resultType"));
		assertEquals(2, result.getJsonArray("skills").size());
		JsonObject first = result.getJsonArray("skills").get(0).getAsJsonObject();
		assertEquals(McpServer.SKILL_URI_SCHEME + "pdf", first.getString("uri"));
		assertEquals("pdf", first.getJsonObject("frontmatter").getString("name"));
		// SKILL.md + 2 个 references
		assertEquals(3, first.getJsonArray("resources").size());
		JsonObject second = result.getJsonArray("skills").get(1).getAsJsonObject();
		assertEquals("dynamic", second.getString("resources"));
		// 缓存提示
		assertEquals("public", result.getString("cacheScope"));
		assertFalse(result.has("nextCursor"));
	}

	/** 分页：首页带 nextCursor，次页取剩余，非法/越界 cursor 为 -32602。 */
	@Test
	public void skillsListPagination() {
		InMemorySkillSource source = new InMemorySkillSource();
		source.add("a", "a", 0).add("b", "b", 0).add("c", "c", 0);
		McpServer server = new McpServer().skillPageSize(2).registerSkillSource(source);

		JsonObject page1 = resultOf(server.dispatch(req(5, "skills/list", statelessParams())));
		assertEquals(2, page1.getJsonArray("skills").size());
		assertTrue(page1.has("nextCursor"));
		String cursor = page1.getString("nextCursor");

		JsonObject params = statelessParams();
		params.put("cursor", cursor);
		JsonObject page2 = resultOf(server.dispatch(req(6, "skills/list", params)));
		assertEquals(1, page2.getJsonArray("skills").size());
		assertEquals(McpServer.SKILL_URI_SCHEME + "c",
			page2.getJsonArray("skills").get(0).getAsJsonObject().getString("uri"));
		assertFalse(page2.has("nextCursor"));

		JsonObject bad = statelessParams();
		bad.put("cursor", "!!!not-base64!!!");
		assertEquals(-32602, errorCode(server.dispatch(req(7, "skills/list", bad))));

		JsonObject beyond = statelessParams();
		beyond.put("cursor", java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString("99".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		assertEquals(-32602, errorCode(server.dispatch(req(8, "skills/list", beyond))));
	}

	/** skills/get 命中与未知。 */
	@Test
	public void skillsGet() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("pdf", "正文", 1));
		JsonObject params = statelessParams();
		params.put("uri", McpServer.SKILL_URI_SCHEME + "pdf");
		JsonObject result = resultOf(server.dispatch(req(9, "skills/get", params)));
		JsonObject skill = result.getJsonObject("skill");
		assertEquals(McpServer.SKILL_URI_SCHEME + "pdf", skill.getString("uri"));
		assertEquals(2, skill.getJsonArray("resources").size());

		JsonObject unknown = statelessParams();
		unknown.put("uri", McpServer.SKILL_URI_SCHEME + "nope");
		assertEquals(-32602, errorCode(server.dispatch(req(10, "skills/get", unknown))));

		assertEquals(-32602, errorCode(server.dispatch(req(11, "skills/get", statelessParams()))));
	}

	/** resources/read 读 SKILL.md 正文（text/markdown）与资源（.md 为 markdown、其余 text/plain）。 */
	@Test
	public void resourcesReadSkillUris() {
		InMemorySkillSource source = new InMemorySkillSource();
		source.add("pdf", "正文内容", 1);
		// 额外一个非 .md 资源，验证 text/plain 分支
		source.resources.put(McpServer.SKILL_URI_SCHEME + "pdf/assets/data.txt", "纯文本");
		McpServer server = new McpServer().registerSkillSource(source);
		JsonObject params = statelessParams();
		params.put("uri", McpServer.SKILL_URI_SCHEME + "pdf/SKILL.md");
		JsonObject result = resultOf(server.dispatch(req(12, "resources/read", params)));
		JsonObject item = result.getJsonArray("contents").get(0).getAsJsonObject();
		assertEquals("text/markdown", item.getString("mimeType"));
		assertEquals("正文内容", item.getString("text"));
		// resources/read 为 CacheableResult
		assertEquals("public", result.getString("cacheScope"));

		JsonObject ref = statelessParams();
		ref.put("uri", McpServer.SKILL_URI_SCHEME + "pdf/references/r0.md");
		JsonObject refResult = resultOf(server.dispatch(req(13, "resources/read", ref)));
		JsonObject refItem = refResult.getJsonArray("contents").get(0).getAsJsonObject();
		assertEquals("text/markdown", refItem.getString("mimeType"));
		assertEquals("参考0", refItem.getString("text"));

		JsonObject txt = statelessParams();
		txt.put("uri", McpServer.SKILL_URI_SCHEME + "pdf/assets/data.txt");
		JsonObject txtResult = resultOf(server.dispatch(req(131, "resources/read", txt)));
		JsonObject txtItem = txtResult.getJsonArray("contents").get(0).getAsJsonObject();
		assertEquals("text/plain", txtItem.getString("mimeType"));
		assertEquals("纯文本", txtItem.getString("text"));
	}

	/** resources/read 未知 URI 与非 skill scheme 为 -32602。 */
	@Test
	public void resourcesReadErrors() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("pdf", "x", 0));
		assertEquals(-32602, errorCode(server.dispatch(req(14, "resources/read", statelessParams()))));
		JsonObject unknown = statelessParams();
		unknown.put("uri", McpServer.SKILL_URI_SCHEME + "pdf/missing.md");
		assertEquals(-32602, errorCode(server.dispatch(req(15, "resources/read", unknown))));
		JsonObject other = statelessParams();
		other.put("uri", "file:///etc/passwd");
		assertEquals(-32602, errorCode(server.dispatch(req(16, "resources/read", other))));
	}

	/** resources/directory/read 仅在来源声明支持时工作。 */
	@Test
	public void directoryRead() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("pdf", "x", 2).directoryRead(true));
		JsonObject params = statelessParams();
		params.put("uri", McpServer.SKILL_URI_SCHEME + "pdf");
		JsonObject result = resultOf(server.dispatch(req(17, "resources/directory/read", params)));
		assertEquals(3, result.getJsonArray("contents").size());

		JsonObject unknown = statelessParams();
		unknown.put("uri", McpServer.SKILL_URI_SCHEME + "nope");
		assertEquals(-32602, errorCode(server.dispatch(req(18, "resources/directory/read", unknown))));
	}

	/** 来源未启用目录读取时返回 -32602。 */
	@Test
	public void directoryReadDisabled() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("pdf", "x", 0).directoryRead(false));
		JsonObject params = statelessParams();
		params.put("uri", McpServer.SKILL_URI_SCHEME + "pdf");
		assertEquals(-32602, errorCode(server.dispatch(req(19, "resources/directory/read", params))));
	}

	/** 多来源按 URI 去重（先注册优先）。 */
	@Test
	public void multipleSourcesDeduplicated() {
		InMemorySkillSource s1 = new InMemorySkillSource().add("shared", "first", 0);
		InMemorySkillSource s2 = new InMemorySkillSource().add("shared", "second", 0).add("only2", "2", 0);
		McpServer server = new McpServer().registerSkillSource(s1).registerSkillSource(s2);
		JsonObject result = resultOf(server.dispatch(req(20, "skills/list", null)));
		assertEquals(2, result.getJsonArray("skills").size());
		JsonObject params = statelessParams();
		params.put("uri", McpServer.SKILL_URI_SCHEME + "shared/SKILL.md");
		JsonObject read = resultOf(server.dispatch(req(21, "resources/read", params)));
		assertEquals("first",
			read.getJsonArray("contents").get(0).getAsJsonObject().getString("text"));
	}

	/** 有状态请求（无 _meta）下 skills/get 仍返回技能，但不强加缓存提示。 */
	@Test
	public void statefulSkillsGetHasNoCacheHints() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("pdf", "x", 0));
		JsonObject params = Json.object();
		params.put("uri", McpServer.SKILL_URI_SCHEME + "pdf");
		JsonObject result = resultOf(server.dispatch(req(22, "skills/get", params)));
		assertTrue(result.has("skill"));
		assertFalse(result.has("ttlMs"));
		// dispatch 兜底仍注入 resultType
		assertEquals("complete", result.getString("resultType"));
	}

	/** resources/list 保持空数组（技能资源经 skills 扩展发现）。 */
	@Test
	public void resourcesListStaysEmpty() {
		McpServer server = new McpServer()
			.registerSkillSource(new InMemorySkillSource().add("pdf", "x", 0));
		JsonObject result = resultOf(server.dispatch(req(23, "resources/list", null)));
		assertEquals(0, result.getJsonArray("resources").size());
	}

	/** McpSkillResource 校验。 */
	@Test
	public void skillResourceValidation() {
		assertNull(new McpSkillResource("u", null, 0).digest());
		assertThrows(IllegalArgumentException.class, () -> new McpSkillResource(" ", null, 0));
		assertThrows(IllegalArgumentException.class, () -> new McpSkillResource("u", null, -1));
	}

	private static void assertThrows(Class<? extends Throwable> expected, Runnable runnable) {
		try {
			runnable.run();
		} catch (Throwable t) {
			assertTrue("期望 " + expected + " 实际 " + t, expected.isInstance(t));
			return;
		}
		throw new AssertionError("未抛出 " + expected);
	}

	/** 确保 JsonElement 导入不被误删（编译占位）。 */
	@Test
	public void importsResolved() {
		JsonElement el = Json.parse("{}");
		assertTrue(el.isObject());
	}
}
