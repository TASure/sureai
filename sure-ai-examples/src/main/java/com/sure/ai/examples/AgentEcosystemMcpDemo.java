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
package com.sure.ai.examples;

import java.util.List;

import com.sure.ai.agent.skill.Skill;
import com.sure.ai.agent.skill.SkillLoader;
import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.catalog.RiskLevel;
import com.sure.ai.agent.tool.catalog.ToolCatalog;
import com.sure.ai.agent.tool.catalog.ToolMetadata;
import com.sure.ai.agent.tool.catalog.ToolSource;
import com.sure.ai.examples.mcp.CatalogMcpToolSource;
import com.sure.ai.examples.mcp.SkillMcpSkillSource;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.server.McpServer;
import com.sure.ai.model.ToolFunction;

/**
 * Agent 生态 over MCP 演示（2.9.0）：把工具注册中心市场（{@link ToolCatalog}）与
 * Agent Skills（{@link SkillLoader}）经官方 Skills 扩展和无状态协议一次性暴露给
 * MCP 客户端，全程离线 dispatch、零真实网络。
 *
 * <p>演示链路：{@code server/discover}（能力声明）→ {@code tools/list}（市场工具+_meta）
 * → {@code tools/call} → {@code skills/list} → {@code skills/get}
 * → {@code resources/read}（SKILL.md 正文与 L3 引用资源）。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class AgentEcosystemMcpDemo {

	private AgentEcosystemMcpDemo() {
	}

	/**
	 * 入口。
	 *
	 * @param args 未使用
	 */
	public static void main(String[] args) {
		// 1) 工具市场：注册一个本地工具并标注标签/风险级别
		ToolCatalog catalog = new ToolCatalog();
		ToolFunction echoFn = new ToolFunction("market.echo", "回显输入文本（工具市场演示）",
			"{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");
		ToolHandler echoHandler = arguments -> "echo: " + arguments.optString("text", "");
		catalog.register(echoFn, echoHandler, ToolMetadata.builder("market.echo")
			.description("回显输入文本（工具市场演示）")
			.addTag("demo")
			.addTag("echo")
			.version("1.0.0")
			.riskLevel(RiskLevel.LOW)
			.source(ToolSource.LOCAL)
			.build());

		// 2) Agent Skills：从 classpath 加载演示技能，并登记一个 L3 引用资源
		List<Skill> skills = SkillLoader.loadFromClasspath("skills-market/skills");
		SkillMcpSkillSource skillSource = new SkillMcpSkillSource(skills)
			.withResource("demo-skill", "references/welcome.md");

		// 3) 组装 MCP Server（市场工具为动态来源，技能走官方 io.modelcontextprotocol/skills 扩展）
		McpServer server = new McpServer().serverInfo("sureai-agent-ecosystem", "2.9.0")
			.registerToolSource(new CatalogMcpToolSource(catalog))
			.registerSkillSource(skillSource);

		// 4) 无状态 discover：声明 tools/resources 与 skills 扩展
		System.out.println("=== server/discover ===");
		System.out.println(server.dispatch(req(1, "server/discover", stateless())));

		// 5) tools/list：市场工具携带标签/风险等 _meta 私有元数据
		System.out.println("=== tools/list ===");
		System.out.println(server.dispatch(req(2, "tools/list", stateless())));

		// 6) tools/call：路由到市场工具
		JsonObject callParams = stateless();
		JsonObject arguments = Json.object();
		arguments.put("text", "hello agent ecosystem");
		callParams.put("name", "market.echo");
		callParams.set("arguments", arguments);
		System.out.println("=== tools/call market.echo ===");
		System.out.println(server.dispatch(req(3, "tools/call", callParams)));

		// 7) skills/list：技能清单（L1 元数据 + 资源摘要 + 缓存提示）
		System.out.println("=== skills/list ===");
		System.out.println(server.dispatch(req(4, "skills/list", stateless())));

		// 8) skills/get：技能详情
		JsonObject getParams = stateless();
		getParams.put("uri", McpServer.SKILL_URI_SCHEME + "demo-skill");
		System.out.println("=== skills/get demo-skill ===");
		System.out.println(server.dispatch(req(5, "skills/get", getParams)));

		// 9) resources/read：SKILL.md 正文（L2）
		JsonObject bodyParams = stateless();
		bodyParams.put("uri", McpServer.SKILL_URI_SCHEME + "demo-skill/SKILL.md");
		System.out.println("=== resources/read SKILL.md ===");
		System.out.println(server.dispatch(req(6, "resources/read", bodyParams)));

		// 10) resources/read：L3 引用资源（按需加载）
		JsonObject refParams = stateless();
		refParams.put("uri", McpServer.SKILL_URI_SCHEME + "demo-skill/references/welcome.md");
		System.out.println("=== resources/read references/welcome.md ===");
		System.out.println(server.dispatch(req(7, "resources/read", refParams)));

		System.out.println("（真实部署：server.start(new HttpMcpServerTransport(8080, \"/mcp\")) 即对外提供服务）");
	}

	/**
	 * 构造无状态请求参数（_meta 声明 2026-07-28 协议版本）。
	 *
	 * @return params 对象
	 */
	private static JsonObject stateless() {
		JsonObject meta = Json.object();
		meta.put(McpServer.META_PROTOCOL_VERSION, McpServer.PROTOCOL_VERSION_STATELESS);
		JsonObject params = Json.object();
		params.set("_meta", meta);
		return params;
	}

	/**
	 * 构造 JSON-RPC 请求帧。
	 *
	 * @param id     请求 id
	 * @param method 方法名
	 * @param params 参数
	 * @return JSON 文本
	 */
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
}
