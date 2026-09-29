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

package com.sure.ai.rag.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.tool.lang.Assert;

/**
 * LLM-as-judge 基础组件：封装「渲染提示词 → 调用模型 → 容错解析」的通用逻辑。
 *
 * <p>所有 RAGAS 风格指标均继承本类，复用 {@link #ask(String)} 与一组容错解析工具。
 * 容错原则：模型调用抛异常或返回空文本时 {@link #ask(String)} 返回 {@code null}，
 * 各解析方法再回退到安全默认值，绝不因单次模型抖动中断整条评估流水线。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public abstract class LlmJudge {

	/** YES 标记正则（英文单词边界，避免匹配 knowledge 等）。 */
	private static final Pattern YES_WORD = Pattern.compile("\\byes\\b");

	/** NO 标记正则。 */
	private static final Pattern NO_WORD = Pattern.compile("\\bno\\b");

	/** 数字提取正则（含小数与负号）。 */
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

	/** 默认 maxTokens。 */
	public static final int DEFAULT_MAX_TOKENS = 512;

	private final AiClient chatClient;
	private final String model;
	private final int maxTokens;

	/**
	 * 构造 Judge。
	 *
	 * @param chatClient 对话客户端
	 * @param model 模型名
	 * @param maxTokens 最大生成 token 数
	 */
	protected LlmJudge(AiClient chatClient, String model, int maxTokens) {
		Assert.notNull(chatClient, "chatClient 不能为 null");
		Assert.notBlank(model, "model 不能为空白");
		this.chatClient = chatClient;
		this.model = model;
		this.maxTokens = maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
	}

	/**
	 * 向模型发送单条 user 提示词并返回文本。
	 *
	 * <p>任何运行期异常（网络、超时、反序列化等）均被捕获，返回 {@code null}，
	 * 由调用方走容错分支。</p>
	 *
	 * @param prompt 提示词
	 * @return 模型文本，失败或为空时返回 {@code null}
	 */
	protected String ask(String prompt) {
		try {
			ChatRequest request = ChatRequest.builder()
					.model(this.model)
					.messages(List.of(ChatMessage.user(prompt)))
					.maxTokens(this.maxTokens)
					.build();
			ChatResponse response = this.chatClient.chat(request);
			String text = response == null ? null : response.firstText();
			if (text == null || text.isBlank()) {
				return null;
			}
			return text.trim();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 解析模型输出为 yes/no 判定。
	 *
	 * <p>优先识别明确的 NO 标记（避免「不支持」误判为「支持」），其次识别 YES 标记；
	 * 无法判定时返回 {@code defaultValue}。</p>
	 *
	 * @param text 模型输出
	 * @param defaultValue 无法判定时的安全默认
	 * @return true 表示 yes/支持/相关，false 表示 no/不支持/无关
	 */
	public static boolean parseYesNo(String text, boolean defaultValue) {
		if (text == null || text.isBlank()) {
			return defaultValue;
		}
		String t = text.toLowerCase();
		if (t.contains("不支持") || t.contains("不相关") || t.contains("否")
				|| NO_WORD.matcher(t).find() || t.contains("negative")) {
			return false;
		}
		if (t.contains("支持") || t.contains("相关") || t.contains("是")
				|| YES_WORD.matcher(t).find() || t.contains("positive")) {
			return true;
		}
		return defaultValue;
	}

	/**
	 * 从模型输出中提取 0~1 分数。
	 *
	 * <p>容忍 0~1 小数与百分制（如 80 → 0.8），结果 clamp 到 [0,1]；
	 * 提取失败返回 {@code defaultValue}。</p>
	 *
	 * @param text 模型输出
	 * @param defaultValue 提取失败时的安全默认
	 * @return [0,1] 分数
	 */
	public static double parseScore01(String text, double defaultValue) {
		if (text == null || text.isBlank()) {
			return defaultValue;
		}
		Matcher m = NUMBER.matcher(text);
		if (!m.find()) {
			return defaultValue;
		}
		try {
			double value = Double.parseDouble(m.group());
			if (value < 0.0) {
				value = 0.0;
			} else if (value > 1.0) {
				// 百分制（>=10，如 80 → 0.8）；(1,10) 视为越界高分，截断为 1.0
				value = (value <= 100.0 && value >= 10.0) ? value / 100.0 : 1.0;
			}
			return value;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * 将模型输出按行拆分为条目（claims / 要点 / 反推问题）。
	 *
	 * <p>去除行号与项目符号（{@code -}、{@code *}、{@code 1.}、{@code 1、} 等），
	 * 过滤空白行。</p>
	 *
	 * @param text 模型输出
	 * @return 条目列表，可能为空
	 */
	public static List<String> parseLineItems(String text) {
		List<String> items = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return items;
		}
		for (String raw : text.split("\\r?\\n")) {
			String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			line = line.replaceFirst("^\\s*[-*•]\\s*", "");
			line = line.replaceFirst("^\\s*\\d+[\\.、\\)]\\s*", "");
			line = line.trim();
			if (!line.isEmpty()) {
				items.add(line);
			}
		}
		return items;
	}

	/**
	 * 当前模型名（子类提示词调试用）。
	 *
	 * @return 模型名
	 */
	protected final String model() {
		return this.model;
	}
}
