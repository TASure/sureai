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

package com.sure.ai.rag.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量提示词模板：以 {@code {varName}} 为占位符的字符串渲染器。
 *
 * <p>不引入 Freemarker/Velocity 等任何模板引擎，仅做正则替换，运行期零第三方依赖。
 * 占位符语法：</p>
 * <ul>
 *   <li>{@code {name}}：必填占位符；</li>
 *   <li>{@code {name=defaultValue}}：带默认值的占位符，变量未提供时使用默认值；</li>
 *   <li>变量值调用 {@code toString()} 渲染，{@code null} 替换为空串。</li>
 * </ul>
 *
 * <p>非严格模式（默认）：未提供的变量原样保留 {@code {name}}，多余变量被忽略；
 * 严格模式（{@link Builder#strict(boolean)}）：缺变量或多余变量均抛
 * {@link IllegalArgumentException}。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class PromptTemplate {

	/** 占位符正则：{name} 或 {name=default}，默认值不含花括号。 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)(?:=([^{}]*))?}");

	private final String template;
	private final boolean strict;
	private final List<String> variableNames;

	private PromptTemplate(Builder builder) {
		this.template = builder.template;
		this.strict = builder.strict;
		this.variableNames = extractVariables(builder.template);
	}

	/**
	 * 从内联模板字符串创建（非严格模式）。
	 *
	 * @param template 模板字符串
	 * @return 模板
	 */
	public static PromptTemplate fromString(String template) {
		return builder().template(template).build();
	}

	/**
	 * 从 classpath 资源文件加载模板（UTF-8 编码，非严格模式）。
	 *
	 * @param classpathPath classpath 路径，例如 {@code "prompts/rag.txt"}
	 * @return 模板
	 */
	public static PromptTemplate fromResource(String classpathPath) {
		return builder().template(readResource(classpathPath)).build();
	}

	/**
	 * 创建 Builder，可开启严格模式。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 提取模板中所有占位符名（去重，保持出现顺序，含带默认值的变量）。
	 *
	 * @return 不可修改变量名列表
	 */
	public List<String> variables() {
		return List.copyOf(this.variableNames);
	}

	/**
	 * 按变量表渲染模板。
	 *
	 * @param variables 变量表，可为 null（视为空表）
	 * @return 渲染结果
	 * @throws IllegalArgumentException 严格模式下缺变量或存在多余变量时
	 */
	public String render(Map<String, ?> variables) {
		Map<String, ?> vars = variables == null ? Map.of() : variables;
		if (this.strict) {
			for (String key : vars.keySet()) {
				if (!this.variableNames.contains(key)) {
					throw new IllegalArgumentException("模板未定义变量: " + key);
				}
			}
		}
		Matcher matcher = PLACEHOLDER.matcher(this.template);
		StringBuilder sb = new StringBuilder();
		while (matcher.find()) {
			String name = matcher.group(1);
			String defaultValue = matcher.group(2);
			String replacement;
			if (vars.containsKey(name)) {
				Object value = vars.get(name);
				replacement = value == null ? "" : value.toString();
			} else if (defaultValue != null) {
				replacement = defaultValue;
			} else if (this.strict) {
				throw new IllegalArgumentException("缺少必填变量: " + name);
			} else {
				replacement = matcher.group();
			}
			matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	/**
	 * 可变参数渲染：{@code render("name", "Alice", "age", "30")}。
	 *
	 * @param keyValues 交替出现的键值对，必须成对
	 * @return 渲染结果
	 * @throws IllegalArgumentException 参数个数不为偶数时
	 */
	public String render(String... keyValues) {
		if (keyValues.length % 2 != 0) {
			throw new IllegalArgumentException("render 的可变参数必须成对出现(key1,value1,key2,value2...)");
		}
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return render(map);
	}

	private static List<String> extractVariables(String template) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		Set<String> names = new LinkedHashSet<>();
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return List.copyOf(names);
	}

	private static String readResource(String classpathPath) {
		try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(classpathPath)) {
			if (in == null) {
				throw new IllegalArgumentException("classpath 资源不存在: " + classpathPath);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("读取模板资源失败: " + classpathPath, e);
		}
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String template;
		private boolean strict;

		private Builder() {
		}

		/**
		 * 设置模板字符串。
		 *
		 * @param template 模板
		 * @return this
		 */
		public Builder template(String template) {
			this.template = template;
			return this;
		}

		/**
		 * 开启严格模式：缺变量或多余变量抛异常。
		 *
		 * @param strict 是否严格
		 * @return this
		 */
		public Builder strict(boolean strict) {
			this.strict = strict;
			return this;
		}

		/**
		 * 构建模板。
		 *
		 * @return 模板
		 */
		public PromptTemplate build() {
			if (this.template == null || this.template.isEmpty()) {
				throw new IllegalArgumentException("template must not be blank");
			}
			return new PromptTemplate(this);
		}
	}
}
