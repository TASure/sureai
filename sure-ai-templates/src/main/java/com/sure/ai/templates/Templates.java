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

import java.util.List;
import java.util.Locale;

/**
 * 模板应用库的模板登记表（v3.0.0）。
 *
 * <p>每个模板是一份内嵌在 classpath 的目录：{@code /templates/&lt;id&gt;/}，
 * 目录内容为一个可直接构建运行的独立 Maven 工程（pom + 主类 + 最小单测 + README）。</p>
 *
 * @author sureai
 * @since 3.0.0
 */
public enum Templates {

	/** 知识库问答（RAG 端到端）：本地文档 → 分块 → 关键词检索 → 拼接上下文生成。 */
	RAG_KB("rag-kb", "知识库问答（RAG 端到端）"),

	/** 客服机器人：ReActAgent + 工具（订单查询 / 退换货规则）+ 会话记忆。 */
	CUSTOMER_SERVICE("customer-service", "客服机器人（ReAct + 工具 + 记忆）"),

	/** 代码助手：结构化输出 + 静态检查工具。 */
	CODE_ASSISTANT("code-assistant", "代码助手（结构化输出 + 工具）");

	private final String id;
	private final String displayName;

	Templates(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	/**
	 * 模板 id（即 classpath 下的目录名）。
	 *
	 * @return 模板 id
	 */
	public String id() {
		return id;
	}

	/**
	 * 模板中文展示名。
	 *
	 * @return 展示名
	 */
	public String displayName() {
		return displayName;
	}

	/**
	 * 按 id 查找模板；未知 id 抛 {@link IllegalArgumentException}。
	 *
	 * @param id 模板 id
	 * @return 模板
	 * @throws IllegalArgumentException 未知模板
	 */
	public static Templates of(String id) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("模板 id 不能为空");
		}
		for (Templates t : values()) {
			if (t.id.equals(id)) {
				return t;
			}
		}
		throw new IllegalArgumentException("未知模板：" + id + "（可用：" + list() + "）");
	}

	/**
	 * 全部模板 id（有序）。
	 *
	 * @return 模板 id 列表
	 */
	public static List<String> list() {
		List<String> ids = new java.util.ArrayList<>(values().length);
		for (Templates t : values()) {
			ids.add(t.id);
		}
		return ids;
	}

	/**
	 * 把任意工程名转成 slug（小写、非字母数字转连字符、去首尾连字符）。
	 *
	 * @param name 原始名称
	 * @return slug
	 */
	public static String slug(String name) {
		if (name == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (char c : name.trim().toLowerCase(Locale.ROOT).toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				sb.append(c);
			} else if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '-') {
				sb.append('-');
			}
		}
		while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
			sb.setLength(sb.length() - 1);
		}
		return sb.toString();
	}
}
