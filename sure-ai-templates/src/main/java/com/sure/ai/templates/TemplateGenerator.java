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

package com.sure.ai.templates;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 模板生成器（v3.0.0）：把内嵌 classpath 的模板目录复制到目标目录并做占位符替换，
 * 一键生成可独立构建运行的 Maven 工程。零第三方依赖（手写替换，不引 freemarker 等）。
 *
 * <p>占位符约定（模板文件内以 {@code {{key}}} 书写）：</p>
 * <ul>
 *   <li>{@code projectName}：工程展示名（必填）；</li>
 *   <li>{@code groupId}：缺省 {@code com.example}；</li>
 *   <li>{@code artifactId}：缺省 = {@code slug(projectName)}；</li>
 *   <li>{@code mainPackage}：缺省 = {@code groupId + "." + 包名化(projectName)}（点分隔，供 package 声明）；</li>
 *   <li>{@code mainPackagePath}：缺省 = {@code mainPackage} 点转斜杠（供源文件目录路径）；</li>
 *   <li>{@code sureaiVersion}：缺省 = 当前 sure-ai 版本（构建期注入，见 {@link #DEFAULT_SUREAI_VERSION}）。</li>
 * </ul>
 *
 * <p>模板文件须为 UTF-8 文本；目标目录已存在且非空时拒绝生成（防覆盖）。
 * 模板中出现但未提供的占位符键保留 {@code {{key}}} 原样，不静默删除。</p>
 *
 * @author sureai
 * @since 3.0.0
 */
public final class TemplateGenerator {

	/** 模板资源根（classpath）。 */
	static final String TEMPLATE_ROOT = "/templates/";

	/**
	 * 生成工程默认依赖的 sure-ai 版本。
	 *
	 * <p>构建期由 Maven 资源过滤把 {@code @project.version@} 注入
	 * {@code sureai-version.properties}；运行期读取之，缺失/损坏时回退到
	 * {@link #FALLBACK_SUREAI_VERSION}（与发布版本同步维护）。</p>
	 */
	public static final String DEFAULT_SUREAI_VERSION = loadDefaultVersion();

	/** 属性文件缺失/未过滤时的最终兜底版本（随发布同步更新）。 */
	static final String FALLBACK_SUREAI_VERSION = "3.0.0";

	private static String loadDefaultVersion() {
		return resolveDefaultVersion(
			TemplateGenerator.class.getResourceAsStream("/sureai-version.properties"));
	}

	/**
	 * 从属性输入流解析 sure-ai 版本（包可见，供测试注入各类分支）。
	 *
	 * @param in 属性输入流；{@code null} 视为缺失
	 * @return 解析出的版本号；输入为 null / 键缺失 / 值为空白 / 读取失败时返回
	 * {@link #FALLBACK_SUREAI_VERSION}
	 */
	static String resolveDefaultVersion(InputStream in) {
		if (in == null) {
			return FALLBACK_SUREAI_VERSION;
		}
		try {
			Properties p = new Properties();
			p.load(in);
			String v = p.getProperty("sureai.version");
			if (v != null && !v.isBlank()) {
				return v.trim();
			}
			return FALLBACK_SUREAI_VERSION;
		} catch (IOException ignored) {
			return FALLBACK_SUREAI_VERSION;
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
				// 流关闭失败可忽略
			}
		}
	}

	private TemplateGenerator() {
		throw new AssertionError("No instances");
	}

	/**
	 * 生成模板工程（使用默认类加载器）。
	 *
	 * @param template     模板 id（见 {@link Templates#list()}）
	 * @param targetDir    目标目录（须不存在或为空）
	 * @param placeholders 占位符键值（可为空，内建缺省兜底）
	 * @return 生成的全部文件路径（按目录遍历序）
	 * @throws IllegalArgumentException 未知模板 / 目标目录非空 / projectName 为空
	 * @throws IOException              资源读取或写入失败
	 */
	public static List<Path> generate(String template, Path targetDir, Map<String, String> placeholders)
			throws IOException {
		return generate(template, targetDir, placeholders,
			Thread.currentThread().getContextClassLoader());
	}

	/**
	 * 生成模板工程（显式类加载器，供自定义资源路径场景与测试使用）。
	 *
	 * @param template     模板 id
	 * @param targetDir    目标目录（须不存在或为空）
	 * @param placeholders 占位符键值（可为空）
	 * @param loader       用于定位模板资源的类加载器
	 * @return 生成的全部文件路径
	 * @throws IllegalArgumentException 未知模板 / 目标目录非空 / projectName 为空
	 * @throws IOException              资源读取或写入失败
	 */
	public static List<Path> generate(String template, Path targetDir, Map<String, String> placeholders,
			ClassLoader loader) throws IOException {
		Templates t = Templates.of(template);
		Path dir = targetDir.toAbsolutePath().normalize();
		if (Files.exists(dir) && !listFiles(dir).isEmpty()) {
			throw new IllegalArgumentException("目标目录已存在且非空，拒绝生成：" + dir);
		}
		Map<String, String> resolved = resolve(placeholders);
		Files.createDirectories(dir);
		List<Path> generated = new ArrayList<>();
		String rootPath = TEMPLATE_ROOT + t.id();
		URL root = loader.getResource(rootPath.substring(1));
		if (root == null) {
			throw new IOException("模板资源不存在：" + rootPath);
		}
		for (TemplateFile file : enumerate(loader, root, rootPath)) {
			// 相对路径同样做占位符替换（目录名可含 {{mainPackagePath}}）
			String rel = apply(file.relativePath, resolved);
			Path target = dir.resolve(rel);
			Path parent = target.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			String content = new String(file.bytes, StandardCharsets.UTF_8);
			content = apply(content, resolved);
			Files.write(target, content.getBytes(StandardCharsets.UTF_8));
			generated.add(target);
		}
		return generated;
	}

	/**
	 * 解析占位符：内建缺省兜底 + 用户覆盖。
	 *
	 * @param placeholders 用户提供的键值
	 * @return 完整替换表
	 */
	static Map<String, String> resolve(Map<String, String> placeholders) {
		Map<String, String> map = placeholders == null
			? new HashMap<>() : new HashMap<>(placeholders);
		String projectName = map.get("projectName");
		if (projectName == null || projectName.isBlank()) {
			throw new IllegalArgumentException("placeholders 必须提供非空的 projectName");
		}
		map.putIfAbsent("groupId", "com.example");
		map.putIfAbsent("artifactId", Templates.slug(projectName));
		map.putIfAbsent("mainPackage",
			map.get("groupId") + "." + Templates.slug(projectName).replace("-", ""));
		map.putIfAbsent("mainPackagePath", map.get("mainPackage").replace('.', '/'));
		map.putIfAbsent("sureaiVersion", DEFAULT_SUREAI_VERSION);
		return map;
	}

	/**
	 * 对文本做占位符替换。
	 *
	 * @param text   原始文本
	 * @param values 替换表
	 * @return 替换后的文本
	 */
	static String apply(String text, Map<String, String> values) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < text.length()) {
			int start = text.indexOf("{{", i);
			if (start < 0) {
				out.append(text, i, text.length());
				break;
			}
			out.append(text, i, start);
			int end = text.indexOf("}}", start + 2);
			if (end < 0) {
				out.append(text, start, text.length());
				break;
			}
			String key = text.substring(start + 2, end);
			String value = values.get(key);
			if (value != null) {
				out.append(value);
			} else {
				// 未知键保留原样
				out.append(text, start, end + 2);
			}
			i = end + 2;
		}
		return out.toString();
	}

	/**
	 * 枚举模板目录下全部文件（file: 目录用 Files.walk，jar: 用 JarFile 过滤）。
	 *
	 * @param loader 类加载器
	 * @param root   模板根 URL
	 * @param prefix 模板根路径前缀
	 * @return 文件清单
	 * @throws IOException 读取失败
	 */
	static List<TemplateFile> enumerate(ClassLoader loader, URL root, String prefix) throws IOException {
		if ("file".equals(root.getProtocol())) {
			Path base;
			try {
				base = Path.of(root.toURI());
			} catch (Exception e) {
				throw new IOException("模板根 URL 转路径失败：" + root, e);
			}
			List<TemplateFile> files = new ArrayList<>();
			try (Stream<Path> walk = Files.walk(base)) {
				for (Path p : walk.filter(Files::isRegularFile).toList()) {
					String rel = base.relativize(p).toString().replace('\\', '/');
					files.add(new TemplateFile(rel, Files.readAllBytes(p)));
				}
			}
			return files;
		}
		if ("jar".equals(root.getProtocol())) {
			String spec = root.toString();
			int bang = spec.indexOf("!/");
			Path jarPath;
			try {
				// jar:file:/...!/ 内嵌部分剥离 jar: 前缀后才是 file: URI（Path.of 仅接受 file scheme）
				String filePart = spec.substring(0, bang).replaceFirst("^jar:", "");
				jarPath = Path.of(URI.create(filePart));
			} catch (Exception e) {
				throw new IOException("jar 模板 URL 转路径失败：" + root, e);
			}
			List<TemplateFile> files = new ArrayList<>();
			try (JarFile jar = new JarFile(jarPath.toFile())) {
				String baseEntry = prefix.substring(1) + "/";
				java.util.Enumeration<JarEntry> entries = jar.entries();
				while (entries.hasMoreElements()) {
					JarEntry e = entries.nextElement();
					String name = e.getName();
					if (!e.isDirectory() && name.startsWith(baseEntry)) {
						String rel = name.substring(baseEntry.length());
						try (InputStream in = jar.getInputStream(e)) {
							files.add(new TemplateFile(rel, in.readAllBytes()));
						}
					}
				}
			}
			return files;
		}
		throw new IOException("不支持的模板资源协议：" + root.getProtocol());
	}

	private static List<Path> listFiles(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).toList();
		}
	}

	/**
	 * 模板内一个文件：相对路径 + 字节内容。
	 */
	static final class TemplateFile {

		final String relativePath;
		final byte[] bytes;

		TemplateFile(String relativePath, byte[] bytes) {
			this.relativePath = relativePath;
			this.bytes = bytes;
		}
	}
}
