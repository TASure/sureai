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

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.sure.tool.lang.Assert;

/**
 * 技能发现器：从本地目录或 classpath（展开目录 / jar 条目）扫描 SKILL.md 并构建
 * {@link Skill} 列表。
 *
 * <p><b>本地目录</b>两种布局：</p>
 * <ul>
 *   <li>根目录自身含 {@code SKILL.md} → 单技能；</li>
 *   <li>根目录下每个直接子目录含 {@code SKILL.md} → 多技能集合（子目录无 SKILL.md 则跳过）。</li>
 * </ul>
 *
 * <p><b>classpath（展开目录）</b>：{@code base} 为资源前缀（如 {@code "skills"}），
 * 遍历 classpath 根目录定位 {@code base/SKILL.md} 单技能或
 * {@code base/<name>/SKILL.md} 集合；jar 打包技能见 {@link #loadJar(Path, String)}。</p>
 *
 * <p>加载期仅解析 Level 1 元数据；正文与资源由 {@link Skill} 按需读取。
 * 同名技能冲突、frontmatter 非法均抛 {@link SkillLoadException}（严格模式，不静默跳过）。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class SkillLoader {

	/** 私有构造（静态工具类）。 */
	private SkillLoader() {
	}

	/**
	 * 从本地目录加载技能。
	 *
	 * @param root 技能根目录（单技能目录或技能集合父目录）
	 * @return 技能列表（发现顺序）
	 * @throws SkillLoadException 目录不存在、frontmatter 非法或技能名冲突
	 */
	public static List<Skill> load(Path root) {
		Assert.notNull(root, "root must not be null");
		if (!Files.isDirectory(root)) {
			throw new SkillLoadException("技能目录不存在或不是目录: " + root);
		}
		List<Skill> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		if (Files.isRegularFile(root.resolve("SKILL.md"))) {
			addLocal(root, out, seen);
			return out;
		}
		try (DirectoryStream<Path> children = Files.newDirectoryStream(root, Files::isDirectory)) {
			for (Path child : children) {
				if (Files.isRegularFile(child.resolve("SKILL.md"))) {
					addLocal(child, out, seen);
				}
			}
		} catch (IOException e) {
			throw new SkillLoadException("扫描技能目录失败: " + root, e);
		}
		return out;
	}

	/**
	 * 从 classpath 加载技能（使用当前类加载器）。
	 *
	 * @param base 资源前缀（无首尾斜杠，如 {@code "skills"}）
	 * @return 技能列表（发现顺序）
	 * @throws SkillLoadException base 非法、frontmatter 非法或技能名冲突
	 */
	public static List<Skill> loadFromClasspath(String base) {
		return loadFromClasspath(base, SkillLoader.class.getClassLoader());
	}

	/**
	 * 从指定类加载器的 classpath 加载技能。
	 *
	 * @param base    资源前缀（无首尾斜杠，如 {@code "skills"}）
	 * @param loader  类加载器（null 回退当前类加载器）
	 * @return 技能列表（发现顺序）
	 * @throws SkillLoadException base 非法、frontmatter 非法或技能名冲突
	 */
	public static List<Skill> loadFromClasspath(String base, ClassLoader loader) {
		Assert.notBlank(base, "base must not be blank");
		ClassLoader cl = loader == null ? SkillLoader.class.getClassLoader() : loader;
		String norm = normalizeBase(base);
		List<Skill> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		try {
			// 遍历全部 classpath 根：file 展开目录直接定位 base 并扫描其子目录
			Enumeration<URL> roots = cl.getResources("");
			while (roots.hasMoreElements()) {
				URL root = roots.nextElement();
				if ("file".equals(root.getProtocol())) {
					scanRootFile(root, norm, cl, out, seen);
				}
			}
		} catch (IOException e) {
			throw new SkillLoadException("遍历 classpath 失败: " + norm, e);
		}
		return out;
	}

	/**
	 * 从显式 jar 文件加载技能：枚举条目匹配 {@code base/SKILL.md} 直挂与
	 * {@code base/<child>/SKILL.md} 嵌套布局。
	 *
	 * <p>说明：classpath 自动枚举依赖类加载器回吐根 URL，标准 URLClassLoader
	 * 不保证回吐 jar 根，故 jar 打包技能提供显式入口；展开目录技能走
	 * {@link #loadFromClasspath(String)}。</p>
	 *
	 * @param jarFile jar 文件路径
	 * @param base    jar 内资源前缀（无首尾斜杠）
	 * @return 技能列表
	 * @throws SkillLoadException jar 打开失败、frontmatter 非法或技能名冲突
	 */
	public static List<Skill> loadJar(Path jarFile, String base) {
		Assert.notNull(jarFile, "jarFile must not be null");
		Assert.notBlank(base, "base must not be blank");
		String norm = normalizeBase(base);
		String prefix = norm + "/";
		List<Skill> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		boolean direct = false;
		Set<String> skillDirs = new LinkedHashSet<>();
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (JarFile jar = new JarFile(jarFile.toFile())) {
			Enumeration<JarEntry> all = jar.entries();
			while (all.hasMoreElements()) {
				JarEntry entry = all.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				String name = entry.getName();
				if (!name.startsWith(prefix)) {
					continue;
				}
				String rest = name.substring(prefix.length());
				if ("SKILL.md".equals(rest)) {
					direct = true;
				} else {
					int slash = rest.indexOf('/');
					if (slash > 0 && "SKILL.md".equals(rest.substring(slash + 1))) {
						skillDirs.add(rest.substring(0, slash));
					}
				}
				try (InputStream in = jar.getInputStream(entry)) {
					entries.put(name, in.readAllBytes());
				} catch (IOException e) {
					throw new SkillLoadException("读取 jar 条目失败: " + name, e);
				}
			}
		} catch (IOException e) {
			throw new SkillLoadException("打开 jar 失败: " + jarFile, e);
		}
		if (direct) {
			addJarEntry(norm, entries, out, seen);
		}
		for (String skillDir : skillDirs) {
			addJarEntry(prefix + skillDir, entries, out, seen);
		}
		return out;
	}

	/**
	 * 基于内存条目映射登记单个 jar 内技能。
	 */
	private static void addJarEntry(String resourceDir, Map<String, byte[]> entries,
			List<Skill> out, Set<String> seen) {
		byte[] skillMd = entries.get(resourceDir + "/SKILL.md");
		String text = new String(skillMd, StandardCharsets.UTF_8);
		SkillManifest manifest = SkillManifest.from(SkillFrontmatterParser.parse(text));
		register(manifest, new JarEntrySkillContent(resourceDir, entries), out, seen);
	}

	/**
	 * 规范化资源前缀：去首尾斜杠。
	 */
	private static String normalizeBase(String base) {
		String n = base.trim();
		while (n.startsWith("/")) {
			n = n.substring(1);
		}
		while (n.endsWith("/")) {
			n = n.substring(0, n.length() - 1);
		}
		if (n.isEmpty()) {
			throw new SkillLoadException("classpath 资源前缀不能为空");
		}
		return n;
	}

	/**
	 * 本地目录：读取 SKILL.md 并加入结果集。
	 */
	private static void addLocal(Path dir, List<Skill> out, Set<String> seen) {
		String text;
		try {
			text = Files.readString(dir.resolve("SKILL.md"), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new SkillLoadException("读取 SKILL.md 失败: " + dir, e);
		}
		SkillManifest manifest = SkillManifest.from(SkillFrontmatterParser.parse(text));
		register(manifest, new LocalSkillContent(dir), out, seen);
	}

	/**
	 * classpath：从资源流读取 SKILL.md 并加入结果集。
	 */
	private static void addFromStream(ClassLoader cl, String resourceDir,
			List<Skill> out, Set<String> seen) {
		String name = resourceDir + "/SKILL.md";
		String text;
		try (InputStream in = cl.getResourceAsStream(name)) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new SkillLoadException("读取 SKILL.md 失败: " + name, e);
		}
		SkillManifest manifest = SkillManifest.from(SkillFrontmatterParser.parse(text));
		register(manifest, new ClasspathSkillContent(resourceDir, cl), out, seen);
	}

	/**
	 * 同名冲突检查并登记。
	 */
	private static void register(SkillManifest manifest, SkillContent content,
			List<Skill> out, Set<String> seen) {
		if (!seen.add(manifest.name())) {
			throw new SkillLoadException("技能名冲突: " + manifest.name());
		}
		out.add(new Skill(manifest, content));
	}

	/**
	 * 扫描 file 协议的 classpath 根：直接定位根下 {@code norm/SKILL.md} 或
	 * {@code norm/<child>/SKILL.md}。
	 */
	private static void scanRootFile(URL root, String norm, ClassLoader cl,
			List<Skill> out, Set<String> seen) {
		Path rootDir;
		try {
			rootDir = Path.of(root.toURI());
		} catch (URISyntaxException e) {
			throw new SkillLoadException("非法的 classpath 根 URL: " + root, e);
		}
		Path baseDir = rootDir.resolve(norm);
		if (!Files.isDirectory(baseDir)) {
			return;
		}
		if (Files.isRegularFile(baseDir.resolve("SKILL.md"))) {
			addFromStream(cl, norm, out, seen);
			return;
		}
		try (DirectoryStream<Path> children = Files.newDirectoryStream(baseDir, Files::isDirectory)) {
			for (Path child : children) {
				if (Files.isRegularFile(child.resolve("SKILL.md"))) {
					addFromStream(cl, norm + "/" + child.getFileName(), out, seen);
				}
			}
		} catch (IOException e) {
			throw new SkillLoadException("扫描 classpath 根目录失败: " + baseDir, e);
		}
	}

}
