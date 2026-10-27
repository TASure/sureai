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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 边界分支覆盖：私有构造 / main 入口 / 输出目录清理 / README 缺失 / 渲染子集边界。
 *
 * @author sureai
 * @since 3.0.0
 */
public class DocSiteGeneratorCoverageTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void privateConstructorsAreGuarded() throws Exception {
		for (Class<?> c : new Class<?>[] {MarkdownRenderer.class, DocSiteGenerator.class}) {
			Constructor<?> ctor = c.getDeclaredConstructor();
			ctor.setAccessible(true);
			try {
				ctor.newInstance();
				throw new AssertionError("私有构造应抛 AssertionError");
			} catch (java.lang.reflect.InvocationTargetException e) {
				assertTrue(e.getCause() instanceof AssertionError);
			}
		}
	}

	@Test
	public void mainGeneratesSiteFromArgs() throws Exception {
		Path docs = tmp.newFolder("mdocs").toPath();
		Files.writeString(docs.resolve("a.md"), "# 标题一\n\n正文段落。\n");
		Path out = tmp.newFolder("mout").toPath();
		// main 入口：先清空输出目录再生成
		Files.writeString(out.resolve("stale.txt"), "x");
		DocSiteGenerator.main(new String[] {docs.toString(), out.toString()});
		assertTrue(Files.isRegularFile(out.resolve("index.html")));
		assertTrue(Files.isRegularFile(out.resolve("a.html")));
		assertFalse("旧文件应被清理", Files.exists(out.resolve("stale.txt")));
	}

	@Test
	public void mainRejectsMissingDocsDir() {
		Path missing = tmp.getRoot().toPath().resolve("no-such-docs");
		try {
			DocSiteGenerator.main(new String[] {missing.toString(), tmp.newFolder().toString()});
			throw new AssertionError("应抛 IOException");
		} catch (java.io.IOException e) {
			assertTrue(e.getMessage().contains("不存在"));
		}
	}

	@Test
	public void parseReadmeIndexReturnsEmptyWhenReadmeMissing() throws Exception {
		Path docs = tmp.newFolder("rdocs").toPath();
		Files.writeString(docs.resolve("x.md"), "# X");
		Map<String, String> index = DocSiteGenerator.parseReadmeIndex(docs);
		assertTrue("无 README 时返回空分类", index.isEmpty());
	}

	@Test
	public void parseReadmeIndexIgnoresNonIndexSections() throws Exception {
		Path root = tmp.newFolder("proot").toPath();
		Path docs = root.resolve("docs");
		Files.createDirectories(docs);
		Files.writeString(docs.resolve("x.md"), "# X");
		Files.writeString(root.resolve("README.md"),
			"## 其他章节\n\n| 分类 | 文档 |\n|---|---|\n| 甲 | [x.md](docs/x.md) |\n"
			+ "\n## 文档索引\n\n| 分类 | 文档 |\n|---|---|\n| 核心 | [x.md](docs/x.md) |\n");
		Map<String, String> index = DocSiteGenerator.parseReadmeIndex(docs);
		assertEquals("仅索引段生效", 1, index.size());
		assertTrue(index.containsKey("x.md"));
		assertEquals("核心", index.get("x.md"));
	}

	@Test
	public void hrVariantsAndRejects() {
		assertTrue("*** 也是分割线", MarkdownRenderer.render("a\n\n***\n\nb").contains("<hr"));
		assertTrue("___ 也是分割线", MarkdownRenderer.render("a\n\n___\n\nb").contains("<hr"));
		assertFalse("长度不足不是分割线",
			MarkdownRenderer.render("a\n\n--\n\nb").contains("<hr"));
		assertFalse("混合字符不是分割线",
			MarkdownRenderer.render("a\n\n-*-\n\nb").contains("<hr"));
	}

	@Test
	public void tableSeparatorRejectsMalformed() {
		// 分隔行缺首/尾竖线或单元格非法 → 按普通段落处理而非表格
		String out = MarkdownRenderer.render("a | b\nnot-sep\nc | d");
		assertTrue(out.contains("<p>"));
		assertFalse("非法分隔行不产生表格", out.contains("<table"));
	}

	@Test
	public void inlineCodeAndLinkWithTitle() {
		String out = MarkdownRenderer.render("看 [文档](x.md \"标题\") 与 `code`");
		assertTrue(out.contains("<a href=\"x.md\""));
		assertTrue(out.contains("<code>code</code>"));
	}

	@Test
	public void summaryTruncatesLongText() throws Exception {
		Path docs = tmp.newFolder("sdocs").toPath();
		StringBuilder longText = new StringBuilder("# T\n\n");
		while (longText.length() < 400) {
			longText.append("很长的一段话用于触发截断逻辑的边界条件。");
		}
		Files.writeString(docs.resolve("long.md"), longText.toString());
		Path out = tmp.newFolder("sout").toPath();
		DocSiteGenerator.generate(docs, out);
		String index = Files.readString(out.resolve("index.html"));
		assertTrue(index.contains("long.html"));
	}

	@Test
	public void summarySkipsFenceAndHeadings() throws Exception {
		Path docs = tmp.newFolder("fdocs").toPath();
		Files.writeString(docs.resolve("f.md"),
			"# 标题\n\n```java\ncode block\n```\n\n正文第一句足够长用于触发截断逻辑的边界分支条件判断。\n");
		Path out = tmp.newFolder("fout").toPath();
		DocSiteGenerator.generate(docs, out);
		String index = Files.readString(out.resolve("index.html"));
		assertTrue(index.contains("f.html"));
		// title 取首个 # 行
		String page = Files.readString(out.resolve("f.html"));
		assertTrue(page.contains("<title>标题"));
	}

	@Test
	public void parseReadmeIndexSkipsRowsWithoutLinks() throws Exception {
		Path root = tmp.newFolder("proot2").toPath();
		Path docs = root.resolve("docs");
		Files.createDirectories(docs);
		Files.writeString(docs.resolve("x.md"), "# X");
		Files.writeString(root.resolve("README.md"),
			"## 文档索引\n\n| 分类 | 文档 |\n|---|---|\n| 空行 | 无链接纯文本 |\n| 核心 | [x.md](docs/x.md) |\n");
		Map<String, String> index = DocSiteGenerator.parseReadmeIndex(docs);
		assertEquals("仅含链接行", 1, index.size());
		assertEquals("核心", index.get("x.md"));
	}

	@Test
	public void generateToNonExistingOutputDir() throws Exception {
		Path docs = tmp.newFolder("ndocs").toPath();
		Files.writeString(docs.resolve("a.md"), "# A\n\n正文\n");
		Path out = tmp.getRoot().toPath().resolve("brand-new-out");
		DocSiteGenerator.generate(docs, out);
		assertTrue(Files.isRegularFile(out.resolve("index.html")));
	}

	@Test
	public void tableSeparatorMalformedCells() {
		// 分隔行 body 含非法单元格（非 :?-{3,}:? 模式）→ 不做表格
		String out = MarkdownRenderer.render("| a | b |\n| - | x |\n| c | d |");
		assertFalse("非法分隔单元格不产生表格", out.contains("<table"));
		// 表头后紧跟普通行 → 非表格
		String out2 = MarkdownRenderer.render("| a | b |\n普通段落行\n| c | d |");
		assertTrue(out2.contains("<p>普通段落行</p>"));
	}

	@Test
	public void imageWithTitleRenders() {
		String out = MarkdownRenderer.render("![示意图](img.png \"图注\") 与 [文](a.md)");
		assertTrue(out.contains("<img alt=\"示意图\" src=\"img.png\""));
		assertTrue(out.contains("<a href=\"a.md\">文</a>"));
	}
}
