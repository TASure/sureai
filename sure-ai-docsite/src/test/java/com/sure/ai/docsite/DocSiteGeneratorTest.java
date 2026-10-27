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

package com.sure.ai.docsite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 文档站生成器测试：分类解析、链接换算、真实站点生成与内链验证。
 */
public class DocSiteGeneratorTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void parseReadmeIndexExtractsCategories() throws IOException {
		Path root = tmp.newFolder("repo").toPath();
		Path docs = Files.createDirectories(root.resolve("docs"));
		Files.writeString(root.resolve("README.md"),
			"# README\n\n## 文档索引\n\n"
			+ "| 主题 | 文档 |\n| --- | --- |\n"
			+ "| Agent / MCP | [agent.md](docs/agent.md) · [mcp.md](docs/mcp.md) |\n"
			+ "| RAG / 向量库 | [rag.md](docs/rag.md) |\n",
			StandardCharsets.UTF_8);
		Map<String, String> map = DocSiteGenerator.parseReadmeIndex(docs);
		assertEquals("Agent / MCP", map.get("agent.md"));
		assertEquals("Agent / MCP", map.get("mcp.md"));
		assertEquals("RAG / 向量库", map.get("rag.md"));
	}

	@Test
	public void convertRelativeRewritesDocLinks() {
		assertEquals("agent.html", DocSiteGenerator.convertRelative("agent.md"));
		assertEquals("agent.html#组件一览",
			DocSiteGenerator.convertRelative("agent.md#组件一览"));
		assertEquals("mcp.html", DocSiteGenerator.convertRelative("docs/mcp.md"));
		assertEquals("index.html", DocSiteGenerator.convertRelative("../README.md"));
		assertEquals("https://example.com/x",
			DocSiteGenerator.convertRelative("https://example.com/x"));
		assertEquals("platforms/openai.md",
			DocSiteGenerator.convertRelative("platforms/openai.md"));
		assertEquals("img/logo.png", DocSiteGenerator.convertRelative("img/logo.png"));
	}

	@Test
	public void generateBuildsSiteWithIndexAndCategoryNavigation() throws IOException {
		Path root = tmp.newFolder("repo2").toPath();
		Path docs = Files.createDirectories(root.resolve("docs"));
		Files.writeString(root.resolve("README.md"),
			"# sureai\n\n## 文档索引\n\n| 主题 | 文档 |\n| --- | --- |\n"
			+ "| 对话能力 | [thinking.md](docs/thinking.md) |\n",
			StandardCharsets.UTF_8);
		Files.writeString(docs.resolve("thinking.md"),
			"# Thinking 模式\n\n这是摘要第一句。\n\n详细内容引用 [agent.md](agent.md)。\n",
			StandardCharsets.UTF_8);
		Files.writeString(docs.resolve("agent.md"),
			"# Agent 编排\n\nAgent 相关内容。\n",
			StandardCharsets.UTF_8);
		Files.writeString(docs.resolve("unlisted.md"),
			"# 未收录文档\n\n独立内容。\n",
			StandardCharsets.UTF_8);

		Path out = tmp.newFolder("site").toPath();
		Path index = DocSiteGenerator.generate(docs, out);

		assertTrue("index.html 存在", Files.exists(index));
		String indexHtml = Files.readString(index);
		assertTrue(indexHtml.contains("sureai 文档站"));
		assertTrue("分类导航含 README 分类", indexHtml.contains("对话能力"));
		assertTrue("未收录归入其他", indexHtml.contains("其他"));

		// 每篇文档页存在
		for (String name : new String[] {"thinking.html", "agent.html", "unlisted.html"}) {
			assertTrue(name + " 生成", Files.exists(out.resolve(name)));
		}
		// 站内相对链接换算
		String thinking = Files.readString(out.resolve("thinking.html"));
		assertTrue(thinking.contains("href=\"agent.html\""));
		// 页面骨架完整
		assertTrue(thinking.contains("<title>Thinking 模式 · sureai 文档站</title>"));
		assertTrue(thinking.contains("style.css"));
		assertTrue(thinking.contains("index.html"));
		// 静态资源与搜索索引
		assertTrue(Files.exists(out.resolve("style.css")));
		assertTrue(Files.exists(out.resolve("search.js")));
		assertTrue(Files.exists(out.resolve("search-index.json")));
	}

	@Test
	public void generateOnRealDocsProducesIndexMatchingReadme() throws IOException {
		// 用仓库真实 docs/ 做端到端断言（与 README 文档索引一致）
		Path repoRoot = Path.of(System.getProperty("user.dir"));
		Path docs = repoRoot.resolve("docs");
		if (!Files.isDirectory(docs)) {
			return; // 非仓库环境跳过
		}
		Path out = tmp.newFolder("real-site").toPath();
		DocSiteGenerator.generate(docs, out);

		assertTrue(Files.exists(out.resolve("index.html")));
		Map<String, String> index = DocSiteGenerator.parseReadmeIndex(docs);
		for (String docName : index.keySet()) {
			String htmlName = docName.replaceFirst("\\.md$", ".html");
			assertTrue("README 索引文档 " + docName + " 应有站点页 " + htmlName,
				Files.exists(out.resolve(htmlName)));
		}
		// 站内相对链接目标存在（抽样全量检查页面内 .html 链接）
		try (var pages = Files.list(out)) {
			for (Path page : pages.filter(p -> p.toString().endsWith(".html")).toList()) {
				String content = Files.readString(page);
				Matcher m = Pattern.compile("href=\"([^\"]+\\.html[^\"]*)\"").matcher(content);
				while (m.find()) {
					String target = m.group(1).split("#")[0];
					assertTrue("页面 " + page.getFileName() + " 的内链 " + target + " 应存在",
						Files.exists(out.resolve(target)));
				}
			}
		}
	}
}
