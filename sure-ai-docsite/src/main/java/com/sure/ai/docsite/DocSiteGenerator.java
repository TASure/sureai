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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档站生成器（v3.0.0）：扫描 {@code docs/} 顶层 Markdown，渲染为静态 HTML 站点
 * （index 首页 + 分类导航 + 站内链接转换 + 纯 JS 客户端搜索），纯 JDK 零第三方依赖。
 *
 * <p>产出目录可直接发布（如 push 到 gh-pages）：</p>
 * <pre>
 *   output/
 *   ├── index.html            # 首页：分类导航 + 搜索框
 *   ├── search-index.json     # 搜索索引（标题/路径/摘要）
 *   ├── style.css             # 站点样式
 *   ├── search.js             # 纯 JS 客户端搜索
 *   ├── agent.html            # 每篇 docs/*.md → *.html
 *   └── platforms/ en/ …     # 子目录原样复制（平台/英文/基准附属资源）
 * </pre>
 *
 * <p>分类骨架从工程根 {@code README.md} 的「## 文档索引」表格解析，保证站点分类
 * 与 README 文档索引一致；未收录的顶层文档归入「其他」。站内 Markdown 相对链接
 * （{@code docs/x.md}、{@code x.md}、{@code ../README.md}）自动换算为 html 链接，
 * 站外 {@code http(s)} 链接原样保留；子目录文档链接保留原样（附属资源按 md 原样复制）。</p>
 *
 * @author sureai
 * @since 3.0.0
 */
public final class DocSiteGenerator {

	/** 站点标题。 */
	static final String SITE_TITLE = "sureai 文档站";

	/** README 文档索引小节标题。 */
	private static final String INDEX_SECTION = "## 文档索引";

	private static final Pattern LINK = Pattern.compile(
		"(href|src)=\"([^\"]+)\"");

	DocSiteGenerator() {
		throw new AssertionError("No instances");
	}

	/**
	 * 生成文档站。
	 *
	 * @param docsRoot   docs 目录（顶层 *.md 转 HTML）
	 * @param outputDir  输出目录（已存在则覆盖）
	 * @return index.html 路径
	 * @throws IOException 读写失败
	 */
	public static Path generate(Path docsRoot, Path outputDir) throws IOException {
		Path root = docsRoot.toAbsolutePath().normalize();
		Path out = outputDir.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new IOException("docs 目录不存在：" + root);
		}
		if (Files.exists(out)) {
			deleteRecursively(out);
		}
		Files.createDirectories(out);

		List<Doc> docs = scanDocs(root);
		Map<String, List<Doc>> categories = categorize(docs, root);
		writeStaticAssets(out);
		writeSearchIndex(out, docs);
		for (Doc doc : docs) {
			writeDocPage(out, doc, docs);
		}
		writeIndex(out, categories);
		return out.resolve("index.html");
	}

	/**
	 * 入口：{@code java com.sure.ai.docsite.DocSiteGenerator [docs根] [输出目录]}。
	 *
	 * @param args 可选 [0]=docs 根（默认 docs）、[1]=输出目录（默认 target/docs-site）
	 * @throws IOException 读写失败
	 */
	public static void main(String[] args) throws IOException {
		String docsArg = args.length > 0 ? args[0] : "docs";
		String outArg = args.length > 1 ? args[1] : "target/docs-site";
		Path index = generate(Path.of(docsArg), Path.of(outArg));
		System.out.println("文档站已生成：" + index);
	}

	private static List<Doc> scanDocs(Path root) throws IOException {
		List<Doc> docs = new ArrayList<>();
		try (var stream = Files.list(root)) {
			List<Path> md = stream.filter(Files::isRegularFile)
				.filter(p -> {
					Path fn = p.getFileName();
					return fn != null && fn.toString().endsWith(".md");
				})
				.sorted()
				.toList();
			for (Path p : md) {
				String content = Files.readString(p, StandardCharsets.UTF_8);
				Path fn = p.getFileName();
				docs.add(new Doc(fn == null ? "" : fn.toString(), content));
			}
		}
		return docs;
	}

	private static Map<String, List<Doc>> categorize(List<Doc> docs, Path root)
			throws IOException {
		Map<String, List<Doc>> result = new LinkedHashMap<>();
		Map<String, String> index = parseReadmeIndex(root);
		for (Doc doc : docs) {
			String category = index.getOrDefault(doc.name, "其他");
			result.computeIfAbsent(category, k -> new ArrayList<>()).add(doc);
		}
		return result;
	}

	/**
	 * 解析工程根 README.md 的「## 文档索引」表格：分类 → 文档名。
	 *
	 * @param docsRoot docs 目录（README 在其父目录）
	 * @return 文档名 → 分类
	 * @throws IOException README 读取失败
	 */
	static Map<String, String> parseReadmeIndex(Path docsRoot) throws IOException {
		Path parent = docsRoot.getParent();
		if (parent == null) {
			return new LinkedHashMap<>();
		}
		Path readme = parent.resolve("README.md");
		Map<String, String> map = new LinkedHashMap<>();
		if (!Files.isRegularFile(readme)) {
			return map;
		}
		List<String> lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
		boolean inSection = false;
		String currentCategory = null;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith("## ")) {
				inSection = trimmed.startsWith(INDEX_SECTION);
				currentCategory = null;
				continue;
			}
			if (!inSection || !trimmed.startsWith("|")) {
				continue;
			}
			// 表格行：| 分类 | [a.md](docs/a.md) · [b.md](docs/b.md) |
			String[] cells = splitCells(trimmed);
			if (cells.length < 2) {
				continue;
			}
			String category = cells[0].trim();
			if (category.isEmpty() || "主题".equals(category) || "文档".equals(category)) {
				continue;
			}
			currentCategory = category;
			Matcher m = Pattern.compile("\\[([^\\]]+)\\]\\(docs/([^)]+\\.md)\\)")
				.matcher(cells[1]);
			while (m.find()) {
				map.put(m.group(2), currentCategory);
			}
		}
		return map;
	}

	private static String[] splitCells(String line) {
		String body = line.trim();
		if (body.startsWith("|")) {
			body = body.substring(1);
		}
		if (body.endsWith("|")) {
			body = body.substring(0, body.length() - 1);
		}
		return body.split("\\|", -1);
	}

	private static void writeDocPage(Path out, Doc doc, List<Doc> all)
			throws IOException {
		String html = MarkdownRenderer.render(doc.content);
		html = rewriteLinks(html);
		String title = doc.title();
		String nav = buildNav(all);
		String page = """
			<!DOCTYPE html>
			<html lang="zh-CN">
			<head>
			<meta charset="UTF-8" />
			<meta name="viewport" content="width=device-width, initial-scale=1.0" />
			<title>%s · %s</title>
			<link rel="stylesheet" href="style.css" />
			</head>
			<body>
			<header class="site-header"><a class="brand" href="index.html">%s</a></header>
			<div class="layout">
			<nav class="sidebar">%s</nav>
			<main class="content">
			<h1 class="page-title">%s</h1>
			%s
			<footer class="page-footer"><a href="index.html">← 返回文档站首页</a></footer>
			</main>
			</div>
			</body>
			</html>
			""";
		String filled = fill(page, escapeHtml(title), SITE_TITLE, SITE_TITLE,
			nav, escapeHtml(title), html);
		Files.writeString(out.resolve(doc.fileNameToHtml()), filled, StandardCharsets.UTF_8);
	}

	private static void writeIndex(Path out, Map<String, List<Doc>> categories)
			throws IOException {
		StringBuilder cards = new StringBuilder();
		for (Map.Entry<String, List<Doc>> entry : categories.entrySet()) {
			cards.append("<section class=\"category\">\n");
			cards.append("<h2>").append(escapeHtml(entry.getKey())).append("</h2>\n");
			for (Doc doc : entry.getValue()) {
				cards.append("<article class=\"card\"><h3><a href=\"")
					.append(doc.fileNameToHtml()).append("\">")
					.append(escapeHtml(doc.title())).append("</a></h3>");
				cards.append("<p>").append(escapeHtml(doc.summary())).append("</p></article>\n");
			}
			cards.append("</section>\n");
		}
		String index = """
			<!DOCTYPE html>
			<html lang="zh-CN">
			<head>
			<meta charset="UTF-8" />
			<meta name="viewport" content="width=device-width, initial-scale=1.0" />
			<title>%s</title>
			<link rel="stylesheet" href="style.css" />
			</head>
			<body>
			<header class="site-header"><a class="brand" href="index.html">%s</a></header>
			<main class="home">
			<section class="hero">
			<h1>%s</h1>
			<p>sureai：Java 多模块 Maven AI 库（JDK 25，零第三方运行期依赖）。
			分类导航来自 README 文档索引，与仓库同步。</p>
			<input id="search-box" type="search" placeholder="搜索文档标题 / 摘要…" />
			<div id="search-results" class="search-results"></div>
			</section>
			%s
			<footer class="site-footer">由 sure-ai-docsite 纯 JDK 生成 · 源文档见 docs/</footer>
			</main>
			<script src="search.js"></script>
			</body>
			</html>
			""";
		String filled = fill(index, SITE_TITLE, SITE_TITLE, SITE_TITLE, cards.toString());
		Files.writeString(out.resolve("index.html"), filled, StandardCharsets.UTF_8);
	}

	private static void writeSearchIndex(Path out, List<Doc> docs) throws IOException {
		StringBuilder sb = new StringBuilder("[\n");
		for (int i = 0; i < docs.size(); i++) {
			Doc doc = docs.get(i);
			sb.append("{\"title\":\"").append(jsonEscape(doc.title()))
				.append("\",\"file\":\"").append(doc.fileNameToHtml())
				.append("\",\"text\":\"").append(jsonEscape(doc.summary(400))).append("\"}");
			sb.append(i == docs.size() - 1 ? "\n" : ",\n");
		}
		sb.append("]\n");
		Files.writeString(out.resolve("search-index.json"), sb.toString(),
			StandardCharsets.UTF_8);
	}

	private static void writeStaticAssets(Path out) throws IOException {
		Files.writeString(out.resolve("style.css"), Style.CSS, StandardCharsets.UTF_8);
		Files.writeString(out.resolve("search.js"), Search.JS, StandardCharsets.UTF_8);
	}

	private static String buildNav(List<Doc> all) {
		StringBuilder sb = new StringBuilder("<a class=\"nav-link\" href=\"index.html\">首页</a>\n");
		for (Doc doc : all) {
			sb.append("<a class=\"nav-link\" href=\"")
				.append(doc.fileNameToHtml()).append("\">")
				.append(escapeHtml(doc.title())).append("</a>\n");
		}
		return sb.toString();
	}

	/**
	 * 站内 Markdown 相对链接换算为 html 链接：
	 * {@code docs/x.md} → {@code x.html}；{@code x.md} → {@code x.html}；
	 * {@code ../README.md} → {@code index.html}；其余相对路径（子目录资源、图片）保留。
	 *
	 * @param html 渲染后的 HTML
	 * @return 链接换算后的 HTML
	 */
	static String rewriteLinks(String html) {
		Matcher m = LINK.matcher(html);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String attr = m.group(1);
			String url = m.group(2);
			String rewritten = url;
			if (!url.startsWith("http://") && !url.startsWith("https://")
				&& !url.startsWith("#") && !url.startsWith("mailto:")) {
				rewritten = convertRelative(url);
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(
				attr + "=\"" + rewritten + "\""));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * 换算单个相对链接。
	 *
	 * @param url 原链接
	 * @return 换算后的链接
	 */
	static String convertRelative(String url) {
		String hash = "";
		String path = url;
		int sharp = url.indexOf('#');
		if (sharp >= 0) {
			hash = url.substring(sharp);
			path = url.substring(0, sharp);
		}
		if ("../README.md".equals(path)) {
			return "index.html" + hash;
		}
		if (path.endsWith(".md")) {
			if (path.startsWith("docs/")) {
				return path.substring("docs/".length())
					.replaceFirst("\\.md$", ".html") + hash;
			}
			if (!path.contains("/")) {
				return path.replaceFirst("\\.md$", ".html") + hash;
			}
		}
		// 子目录资源（platforms/en/benchmarks 原样复制）或非 md 目标：保留原路径
		return url;
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}


	/** 顺序替换 {\u0040code %s} 占位符（参数内容原样插入，避免 printf 换行语义）。 */
	private static String fill(String template, String... args) {
		String s = template;
		for (String a : args) {
			s = s.replaceFirst("\\%s", java.util.regex.Matcher.quoteReplacement(a));
		}
		return s;
	}

	static String escapeHtml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String jsonEscape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r");
	}

	/**
	 * 一篇文档：文件名 + 原始内容。
	 */
	static final class Doc {

		final String name;
		final String content;

		Doc(String name, String content) {
			this.name = name;
			this.content = content;
		}

		String fileNameToHtml() {
			return name.replaceFirst("\\.md$", ".html");
		}

		String title() {
			for (String line : content.split("\n", -1)) {
				String t = line.trim();
				if (t.startsWith("# ")) {
					return t.substring(2).trim();
				}
			}
			return name.replaceFirst("\\.md$", "");
		}

		String summary() {
			return summary(120);
		}

		String summary(int max) {
			StringBuilder text = new StringBuilder();
			boolean inFence = false;
			for (String line : content.split("\n", -1)) {
				String t = line.trim();
				if (t.startsWith("```")) {
					inFence = !inFence;
					continue;
				}
				if (inFence || t.startsWith("#") || t.isEmpty()) {
					continue;
				}
				text.append(t).append(' ');
				if (text.length() > max) {
					break;
				}
			}
			String s = text.toString().trim();
			return s.length() > max ? s.substring(0, max) + "…" : s;
		}
	}
}
