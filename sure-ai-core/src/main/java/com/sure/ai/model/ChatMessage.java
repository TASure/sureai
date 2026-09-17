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

package com.sure.ai.model;

import java.util.List;

/**
 * 对话消息。
 *
 * <p>不可变；content 与 parts 二选一，name/toolCallId/toolCalls 视角色而定。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class ChatMessage {

	private final Role role;
	private final String content;
	private final List<MessagePart> parts;
	private final String name;
	private final String toolCallId;
	private final List<ToolCall> toolCalls;
	private final String reasoningContent;

	/**
	 * 私有全参构造器。
	 */
	private ChatMessage(Role role, String content, List<MessagePart> parts,
			String name, String toolCallId, List<ToolCall> toolCalls, String reasoningContent) {
		this.role = role;
		this.content = content;
		this.parts = parts == null ? null : List.copyOf(parts);
		this.name = name;
		this.toolCallId = toolCallId;
		this.toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
		this.reasoningContent = reasoningContent;
	}

	/**
	 * 构造系统消息。
	 *
	 * @param content 系统指令
	 * @return 消息
	 */
	public static ChatMessage system(String content) {
		return new ChatMessage(Role.SYSTEM, content, null, null, null, null, null);
	}

	/**
	 * 构造用户文本消息。
	 *
	 * @param content 文本
	 * @return 消息
	 */
	public static ChatMessage user(String content) {
		return new ChatMessage(Role.USER, content, null, null, null, null, null);
	}

	/**
	 * 构造用户多模态消息。
	 *
	 * @param parts 消息片段
	 * @return 消息
	 */
	public static ChatMessage user(List<MessagePart> parts) {
		return new ChatMessage(Role.USER, null, parts, null, null, null, null);
	}

	/**
	 * 构造助手文本消息。
	 *
	 * @param content 文本
	 * @return 消息
	 */
	public static ChatMessage assistant(String content) {
		return new ChatMessage(Role.ASSISTANT, content, null, null, null, null, null);
	}

	/**
	 * 构造助手工具调用消息。
	 *
	 * @param toolCalls 工具调用列表
	 * @return 消息
	 */
	public static ChatMessage assistant(List<ToolCall> toolCalls) {
		return new ChatMessage(Role.ASSISTANT, null, null, null, null, toolCalls, null);
	}

	/**
	 * 构造工具返回消息。
	 *
	 * @param toolCallId 工具调用 ID
	 * @param content    工具返回内容
	 * @return 消息
	 */
	public static ChatMessage tool(String toolCallId, String content) {
		return new ChatMessage(Role.TOOL, content, null, null, toolCallId, null, null);
	}

	/**
	 * 全参构造器（Builder 使用）。
	 *
	 * @param role        角色
	 * @param content     文本内容
	 * @param parts       多模态片段
	 * @param name        名称
	 * @param toolCallId  工具调用 ID
	 * @param toolCalls   工具调用
	 * @return 消息
	 */
	public static ChatMessage of(Role role, String content, List<MessagePart> parts,
			String name, String toolCallId, List<ToolCall> toolCalls) {
		return new ChatMessage(role, content, parts, name, toolCallId, toolCalls, null);
	}

	/**
	 * 全参构造器（含思考内容）。
	 *
	 * @param role             角色
	 * @param content          文本内容
	 * @param parts            多模态片段
	 * @param name             名称
	 * @param toolCallId       工具调用 ID
	 * @param toolCalls        工具调用
	 * @param reasoningContent 思考内容（OpenAI reasoning_content / Gemini thinking）
	 * @return 消息
	 */
	public static ChatMessage of(Role role, String content, List<MessagePart> parts,
			String name, String toolCallId, List<ToolCall> toolCalls, String reasoningContent) {
		return new ChatMessage(role, content, parts, name, toolCallId, toolCalls, reasoningContent);
	}

	/**
	 * 角色。
	 *
	 * @return 角色
	 */
	public Role role() {
		return this.role;
	}

	/**
	 * 文本内容。
	 *
	 * @return 内容，可能为 null
	 */
	public String content() {
		return this.content;
	}

	/**
	 * 多模态片段。
	 *
	 * @return 片段列表，可能为 null
	 */
	public List<MessagePart> parts() {
		return this.parts;
	}

	/**
	 * 名称。
	 *
	 * @return 名称，可能为 null
	 */
	public String name() {
		return this.name;
	}

	/**
	 * 工具调用 ID。
	 *
	 * @return 工具调用 ID，可能为 null
	 */
	public String toolCallId() {
		return this.toolCallId;
	}

	/**
	 * 工具调用列表。
	 *
	 * @return 工具调用，可能为 null
	 */
	public List<ToolCall> toolCalls() {
		return this.toolCalls;
	}

	/**
	 * 思考内容（OpenAI reasoning_content / Gemini thinking 部分）。
	 *
	 * @return 思考内容，普通模型不返回时为 null
	 */
	public String reasoningContent() {
		return this.reasoningContent;
	}
}
