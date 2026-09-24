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

package com.sure.ai.client.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * 对话请求归一化与缓存键计算。
 *
 * <p>归一化策略（参与 hash 的字段，按序拼接）：</p>
 * <ul>
 *   <li>{@code model}</li>
 *   <li>{@code messages}（按请求顺序，逐条序列化 role + content/parts + name +
 *       toolCallId + toolCalls）；content 为 null 时退化为 parts 序列化，
 *       保证「等价请求」hash 一致</li>
 *   <li>{@code temperature}、{@code topP}</li>
 *   <li>{@code tools}（先按 function.name 升序排序，再序列化 name/description/parameters，
 *       保证工具列表顺序无关）</li>
 *   <li>{@code responseFormat}、{@code reasoningEffort}、{@code thinkingConfig}、
 *       {@code grounding}</li>
 * </ul>
 *
 * <p>不参与 hash 的字段：</p>
 * <ul>
 *   <li>{@code maxTokens}：仅限制输出长度，不影响内容语义</li>
 *   <li>{@code n}：候选数，缓存仅保留第一条结果</li>
 *   <li>{@code stream}：流式响应不缓存</li>
 *   <li>{@code extraHeaders}：传输层请求头，与响应内容无关</li>
 * </ul>
 *
 * <p>最终对归一化字符串计算 SHA-256 hex（64 字符）作为缓存键，零第三方依赖。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class ChatCacheKey {

	/** 字段分隔符（控制字符，避免与正文内容冲突）。 */
	private static final char FIELD_SEP = '\u0001';
	/** 列表项分隔符。 */
	private static final char ITEM_SEP = '\u0002';

	private ChatCacheKey() {
	}

	/**
	 * 计算请求的缓存键（SHA-256 hex，64 字符小写）。
	 *
	 * @param request 对话请求
	 * @return 64 字符 hex 摘要
	 */
	public static String of(ChatRequest request) {
		return sha256Hex(normalize(request));
	}

	/**
	 * 返回归一化字符串（便于测试断言顺序无关性与字段参与性）。
	 *
	 * @param request 对话请求
	 * @return 归一化后的稳定字符串
	 */
	public static String normalize(ChatRequest request) {
		StringBuilder sb = new StringBuilder(512);
		append(sb, request.model());
		sb.append(FIELD_SEP);
		appendMessages(sb, request.messages());
		sb.append(FIELD_SEP);
		append(sb, String.valueOf(request.temperature()));
		sb.append(FIELD_SEP);
		append(sb, String.valueOf(request.topP()));
		sb.append(FIELD_SEP);
		appendTools(sb, request.tools());
		sb.append(FIELD_SEP);
		appendJson(sb, request.responseFormat());
		sb.append(FIELD_SEP);
		append(sb, request.reasoningEffort());
		sb.append(FIELD_SEP);
		appendJson(sb, request.thinkingConfig());
		sb.append(FIELD_SEP);
		appendJson(sb, request.grounding());
		return sb.toString();
	}

	/** 按序序列化消息列表。 */
	private static void appendMessages(StringBuilder sb, List<ChatMessage> messages) {
		for (int i = 0; i < messages.size(); i++) {
			ChatMessage m = messages.get(i);
			if (i > 0) {
				sb.append(ITEM_SEP);
			}
			sb.append(m.role() == null ? "" : m.role().value());
			sb.append(FIELD_SEP);
			if (m.content() != null) {
				append(sb, m.content());
			} else {
				appendParts(sb, m.parts());
			}
			sb.append(FIELD_SEP);
			append(sb, m.name());
			sb.append(FIELD_SEP);
			append(sb, m.toolCallId());
			sb.append(FIELD_SEP);
			appendToolCalls(sb, m.toolCalls());
		}
	}

	/** 序列化多模态片段。 */
	private static void appendParts(StringBuilder sb, List<MessagePart> parts) {
		if (parts == null) {
			return;
		}
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				sb.append(ITEM_SEP);
			}
			MessagePart p = parts.get(i);
			if (p instanceof TextPart tp) {
				sb.append("text:");
				append(sb, tp.text());
			} else if (p instanceof ImagePart ip) {
				sb.append("img:");
				append(sb, ip.resolvedUrl());
			} else if (p instanceof DocumentPart dp) {
				sb.append("doc:");
				append(sb, dp.fileId());
				sb.append(',');
				append(sb, dp.name());
				sb.append(',');
				append(sb, dp.mimeType());
				sb.append(',');
				append(sb, dp.data());
			}
		}
	}

	/** 序列化工具调用列表。 */
	private static void appendToolCalls(StringBuilder sb, List<ToolCall> calls) {
		if (calls == null) {
			return;
		}
		for (int i = 0; i < calls.size(); i++) {
			if (i > 0) {
				sb.append(ITEM_SEP);
			}
			ToolCall c = calls.get(i);
			append(sb, c.id());
			sb.append(',');
			append(sb, c.name());
			sb.append(',');
			append(sb, c.argumentsJson());
		}
	}

	/** 工具列表按 function.name 排序后序列化，保证参数顺序无关。 */
	private static void appendTools(StringBuilder sb, List<ToolSpec> tools) {
		if (tools == null || tools.isEmpty()) {
			return;
		}
		List<ToolFunction> fns = new ArrayList<>(tools.size());
		for (ToolSpec t : tools) {
			fns.add(t.function());
		}
		fns.sort(Comparator.comparing(ToolFunction::name,
			Comparator.nullsLast(Comparator.naturalOrder())));
		for (int i = 0; i < fns.size(); i++) {
			if (i > 0) {
				sb.append(ITEM_SEP);
			}
			ToolFunction fn = fns.get(i);
			append(sb, fn.name());
			sb.append(',');
			append(sb, fn.description());
			sb.append(',');
			append(sb, fn.parameters());
		}
	}

	/** 以 canonical JSON 形式序列化对象型字段（responseFormat/grounding 等）。 */
	private static void appendJson(StringBuilder sb, Object value) {
		if (value == null) {
			return;
		}
		append(sb, Json.toElement(value).toString());
	}

	/** 追加字符串（null 安全）。 */
	private static void append(StringBuilder sb, String value) {
		sb.append(value == null ? "" : value);
	}

	/** SHA-256 hex 摘要。 */
	private static String sha256Hex(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
