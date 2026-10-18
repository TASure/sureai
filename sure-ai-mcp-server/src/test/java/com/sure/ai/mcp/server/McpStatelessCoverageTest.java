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

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpError;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.mcp.server.skill.McpSkillInfo;
import com.sure.ai.mcp.server.skill.McpSkillResource;
import com.sure.ai.mcp.server.skill.McpSkillSource;

/**
 * 2.9.0 无状态/技能/工具市场分支补测：错误码、注解序列化、分页游标、
 * 协议版本帧校验、容错与关闭路径。
 *
 * @author sureai
 * @since 2.9.0
 */
public class McpStatelessCoverageTest {

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

	private static JsonObject metaParams(String version) {
		JsonObject meta = Json.object();
		meta.put(McpServer.META_PROTOCOL_VERSION, version);
		JsonObject params = Json.object();
		params.set("_meta", meta);
		return params;
	}

	/** skillPageSize 非正拒绝。 */
	@Test
	public void invalidPageSizeRejected() {
		assertThrows(IllegalArgumentException.class, () -> new McpServer().skillPageSize(0));
	}

	/** 全 hint 与 title 的注解序列化完整覆盖。 */
	@Test
	public void fullAnnotationsSerialized() {
		McpServerTool tool = McpServerTool.builder("a", args -> new McpToolResult(false, List.of("x")))
			.title(" 顶层不应空 ")
			.annotations(McpToolAnnotations.builder()
				.title("注解标题")
				.readOnlyHint(true)
				.destructiveHint(true)
				.idempotentHint(true)
				.openWorldHint(true)
				.build())
			.build();
		McpServer server = new McpServer().registerTool(tool);
		JsonObject first = resultOf(server.dispatch(req(1, "tools/list", null)))
			.getJsonArray("tools").get(0).getAsJsonObject();
		// 顶层 title 优先于注解 title
		assertEquals(" 顶层不应空 ", first.getString("title"));
		JsonObject ann = first.getJsonObject("annotations");
		assertTrue(ann.getBoolean("readOnlyHint"));
		assertTrue(ann.getBoolean("destructiveHint"));
		assertTrue(ann.getBoolean("idempotentHint"));
		assertTrue(ann.getBoolean("openWorldHint"));
	}

	/** 顶层无 title 时取注解 title。 */
	@Test
	public void titleFallsBackToAnnotations() {
		McpServerTool tool = McpServerTool.builder("b", args -> new McpToolResult(false, List.of("x")))
			.annotations(McpToolAnnotations.builder().title("仅注解标题").build())
			.build();
		JsonObject first = resultOf(new McpServer().registerTool(tool).dispatch(req(2, "tools/list", null)))
			.getJsonArray("tools").get(0).getAsJsonObject();
		assertEquals("仅注解标题", first.getString("title"));
	}

	/** 注解对象无任何 hint 时不输出 annotations。 */
	@Test
	public void emptyAnnotationsOmitted() {
		McpServerTool tool = McpServerTool.builder("c", args -> new McpToolResult(false, List.of("x")))
			.annotations(new McpToolAnnotations(null, null, null, null, null))
			.build();
		JsonObject first = resultOf(new McpServer().registerTool(tool).dispatch(req(3, "tools/list", null)))
			.getJsonArray("tools").get(0).getAsJsonObject();
		assertFalse(first.has("annotations"));
		assertFalse(first.has("title"));
	}

	/** tools/call 透传非空 arguments；arguments 为非对象时退化为空对象。 */
	@Test
	public void toolsCallArgumentsPassedThrough() {
		List<JsonObject> seen = new java.util.ArrayList<>();
		McpServerTool tool = McpServerTool.builder("echo", args -> {
			seen.add(args);
			return new McpToolResult(false, List.of("ok"));
		}).build();
		JsonObject params = Json.object();
		params.put("name", "echo");
		JsonObject args = Json.object();
		args.put("msg", "hi");
		params.set("arguments", args);
		JsonObject result = resultOf(new McpServer().registerTool(tool).dispatch(req(4, "tools/call", params)));
		assertEquals("ok", result.getJsonArray("content").get(0).getAsJsonObject().getString("text"));
		assertEquals("hi", seen.get(0).optString("msg", null));

		// arguments 为非对象（字符串）时退化空对象，不抛错
		JsonObject bad = Json.object();
		bad.put("name", "echo");
		bad.put("arguments", "not-object");
		assertFalse(resultOf(new McpServer().registerTool(tool).dispatch(req(41, "tools/call", bad)))
			.getBoolean("isError"));
		assertFalse(seen.get(1).has("msg"));
	}

	/** 动态来源 tools() 返回 null 时，tools/call 未知工具仍 -32602。 */
	@Test
	public void resolveToolWithNullSourceList() {
		McpServer server = new McpServer().registerToolSource(() -> null);
		JsonObject params = Json.object();
		params.put("name", "ghost");
		params.set("arguments", Json.object());
		assertEquals(-32602, errorCode(server.dispatch(req(5, "tools/call", params))));
	}

	/** 动态来源 tools() 抛异常时 dispatch 兜底 -32603。 */
	@Test
	public void sourceThrowingYieldsInternalError() {
		McpServer server = new McpServer().registerToolSource(() -> {
			throw new IllegalStateException("来源不可用");
		});
		assertEquals(-32603, errorCode(server.dispatch(req(6, "tools/list", null))));
	}

	/** 技能来源 listSkills 返回 null 容错。 */
	@Test
	public void nullSkillListTolerated() {
		McpServer server = new McpServer().registerSkillSource(new McpSkillSource() {
			@Override
			public List<McpSkillInfo> listSkills() {
				return null;
			}

			@Override
			public McpSkillInfo getSkill(String uri) {
				return null;
			}

			@Override
			public String readResource(String uri) {
				return null;
			}
		});
		JsonObject result = resultOf(server.dispatch(req(7, "skills/list", null)));
		assertEquals(0, result.getJsonArray("skills").size());
	}

	/** 最简来源（不重写 default）：directoryRead=false、listDirectory 抛 UOE。 */
	@Test
	public void minimalSourceDefaults() {
		McpSkillSource minimal = new McpSkillSource() {
			@Override
			public List<McpSkillInfo> listSkills() {
				return List.of();
			}

			@Override
			public McpSkillInfo getSkill(String uri) {
				return null;
			}

			@Override
			public String readResource(String uri) {
				return null;
			}
		};
		assertFalse(minimal.directoryRead());
		assertThrows(UnsupportedOperationException.class, () -> minimal.listDirectory("skill://x"));
		// discover 中 directoryRead=false
		JsonObject caps = resultOf(new McpServer().registerSkillSource(minimal)
			.dispatch(req(8, "server/discover", null))).getJsonObject("capabilities");
		assertFalse(caps.getJsonObject("extensions")
			.getJsonObject(McpServer.EXTENSION_SKILLS).getBoolean("directoryRead"));
	}

	/** skills/get 无来源 -32601；params 缺 uri -32602。 */
	@Test
	public void skillsGetMissingSourceAndUri() {
		assertEquals(-32601, errorCode(new McpServer().dispatch(req(9, "skills/get", null))));
		McpServer server = new McpServer().registerSkillSource(new McpSkillsOverMcpTest.InMemorySkillSource());
		assertEquals(-32602, errorCode(server.dispatch(req(10, "skills/get", null))));
	}

	/** resources/read 与 resources/directory/read 缺 uri -32602。 */
	@Test
	public void resourcesMissingUri() {
		McpServer server = new McpServer()
			.registerSkillSource(new McpSkillsOverMcpTest.InMemorySkillSource().add("s", "x", 0));
		assertEquals(-32602, errorCode(server.dispatch(req(11, "resources/read", null))));
		assertEquals(-32602, errorCode(server.dispatch(req(12, "resources/directory/read", null))));
	}

	/** listDirectory 抛 IllegalArgumentException 映射 -32602。 */
	@Test
	public void directoryListingFailureIsInvalidParams() {
		McpSkillSource source = new McpSkillsOverMcpTest.InMemorySkillSource() {
			{
				add("s", "x", 0);
				directoryRead(true);
			}

			@Override
			public List<McpSkillResource> listDirectory(String uri) {
				throw new IllegalArgumentException("坏目录");
			}
		};
		McpServer server = new McpServer().registerSkillSource(source);
		JsonObject params = Json.object();
		params.put("uri", McpServer.SKILL_URI_SCHEME + "s");
		assertEquals(-32602, errorCode(server.dispatch(req(13, "resources/directory/read", params))));
	}

	/** frontmatter 为 null 时序列化为空对象；resources 为 null 时空清单。 */
	@Test
	public void skillWithNullFrontmatter() {
		McpSkillInfo info = McpSkillInfo.of(McpServer.SKILL_URI_SCHEME + "n", null, null);
		McpSkillSource source = new McpSkillSource() {
			@Override
			public List<McpSkillInfo> listSkills() {
				return List.of(info);
			}

			@Override
			public McpSkillInfo getSkill(String uri) {
				return info.uri().equals(uri) ? info : null;
			}

			@Override
			public String readResource(String uri) {
				return null;
			}
		};
		McpServer server = new McpServer().registerSkillSource(source);
		JsonObject skill = resultOf(server.dispatch(req(14, "skills/list", null)))
			.getJsonArray("skills").get(0).getAsJsonObject();
		assertEquals(0, skill.getJsonObject("frontmatter").size());
	}

	/** 负偏移游标 -32602。 */
	@Test
	public void negativeCursorRejected() {
		McpServer server = new McpServer().skillPageSize(1)
			.registerSkillSource(new McpSkillsOverMcpTest.InMemorySkillSource().add("s", "x", 0));
		JsonObject params = Json.object();
		params.put("cursor", java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString("-5".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		assertEquals(-32602, errorCode(server.dispatch(req(15, "skills/list", params))));
	}

	/** 帧级 _meta 声明不支持版本 → -32022，data 含 supported/requested。 */
	@Test
	public void frameUnsupportedVersionRejected() {
		String resp = new McpServer().dispatch(req(16, "tools/list", metaParams("1999-01-01")));
		JsonObject error = Json.parse(resp).getAsJsonObject().getJsonObject("error");
		assertEquals(McpError.UNSUPPORTED_PROTOCOL_VERSION, error.getInt("code"));
		assertEquals("1999-01-01", error.getJsonObject("data").getString("requested"));
	}

	/** 非对象帧/数组帧返回 -32600 且 id 为 null（覆盖错误帧 null id 分支）。 */
	@Test
	public void invalidFrameReturnsNullIdError() {
		JsonObject error = Json.parse(new McpServer().dispatch("[1,2]")).getAsJsonObject()
			.getJsonObject("error");
		assertEquals(McpError.INVALID_REQUEST, error.getInt("code"));
		assertTrue(Json.parse(new McpServer().dispatch("[1,2]")).getAsJsonObject().get("id").isNull());
	}

	/** close() 中单个传输关闭抛异常不影响其余。 */
	@Test
	public void closeSwallowsTransportFailure() throws Exception {
		McpServer server = new McpServer();
		Field f = McpServer.class.getDeclaredField("transports");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<AutoCloseable> transports = (List<AutoCloseable>) f.get(server);
		transports.add(() -> {
			throw new java.io.IOException("关闭失败");
		});
		server.close(); // 不抛异常即通过
	}

	/** McpSkillInfo 空 URI 拒绝。 */
	@Test
	public void skillInfoBlankUriRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> McpSkillInfo.of("  ", Json.object(), List.of()));
	}

	/** McpSkillResource 非负 size 与空白 digest 边界。 */
	@Test
	public void skillResourceBoundaries() {
		McpSkillResource r = new McpSkillResource("skill://x/SKILL.md", "", 0);
		assertEquals("", r.digest());
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
}
