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

package com.sure.ai.framework.advisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * Advisor 链一次执行的可变上下文：承载请求模板、可改写的消息列表、响应、
 * 工具执行器与结构化输出目标类型。
 *
 * <p>{@link ChatRequest} 本身不可变，因此本上下文把「消息列表」单独抽成可变
 * {@link ArrayList}——工具循环回填 tool 结果、校验 advisor 追加修正指令都直接改这份列表，
 * 终端真正发请求时通过 {@link #rebuildRequest()} 以原请求其余字段 + 当前消息列表重建。
 * 其余请求字段（model / temperature / tools / responseFormat 等）始终沿用基线请求快照。</p>
 *
 * <p>上下文由框架在每次代理调用时新建，<b>不复用、不跨线程共享</b>；Advisor 实现可在其上
 * 通过 {@link #attribute} 挂自定义中间状态。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class AdvisorContext {

	private final ChatRequest baseRequest;

	private final List<ChatMessage> messages;

	private final ToolExecutor toolExecutor;

	private final Class<?> expectedType;

	private final Map<String, Object> attributes = new LinkedHashMap<>();

	private ChatResponse response;

	/**
	 * 由框架构造。
	 *
	 * @param baseRequest   基线请求（模型 / 工具 / 响应格式等模板字段）
	 * @param toolExecutor  工具执行器（无 @Tool 方法或未启用工具循环时为 {@code null}）
	 * @param expectedType  结构化输出目标 record 类型（普通文本调用为 {@code null}）
	 */
	public AdvisorContext(ChatRequest baseRequest, ToolExecutor toolExecutor, Class<?> expectedType) {
		this.baseRequest = baseRequest;
		this.messages = new ArrayList<>(baseRequest.messages());
		this.toolExecutor = toolExecutor;
		this.expectedType = expectedType;
	}

	/**
	 * 基线请求（模板字段快照；消息未必是最新——消息请读 {@link #messages()}）。
	 *
	 * @return 基线请求
	 */
	public ChatRequest baseRequest() {
		return this.baseRequest;
	}

	/**
	 * 当前可变消息列表（工具回填 / 修正指令直接改这里）。
	 *
	 * @return 可变消息列表
	 */
	public List<ChatMessage> messages() {
		return this.messages;
	}

	/**
	 * 工具执行器（可能为 {@code null}，此时 ToolCallingAdvisor 不触发循环）。
	 *
	 * @return 工具执行器或 {@code null}
	 */
	public ToolExecutor toolExecutor() {
		return this.toolExecutor;
	}

	/**
	 * 结构化输出目标类型（普通文本调用为 {@code null}）。
	 *
	 * @return 目标 record 类型或 {@code null}
	 */
	public Class<?> expectedType() {
		return this.expectedType;
	}

	/**
	 * 当前响应（链执行过程中由终端或短路 advisor 写入）。
	 *
	 * @return 响应，可能为 {@code null}
	 */
	public ChatResponse response() {
		return this.response;
	}

	/**
	 * 写入当前响应（短路 advisor 用）。
	 *
	 * @param response 响应
	 */
	public void response(ChatResponse response) {
		this.response = response;
	}

	/**
	 * 挂自定义中间状态。
	 *
	 * @param key   键
	 * @param value 值
	 */
	public void attribute(String key, Object value) {
		this.attributes.put(key, value);
	}

	/**
	 * 读自定义中间状态。
	 *
	 * @param key 键
	 * @return 值，不存在为 {@code null}
	 */
	public Object attribute(String key) {
		return this.attributes.get(key);
	}

	/**
	 * 以基线请求模板字段 + 当前可变消息列表重建终端请求。
	 *
	 * @return 重建后的请求
	 */
	public ChatRequest rebuildRequest() {
		ChatRequest b = this.baseRequest;
		ChatRequest.Builder rb = ChatRequest.builder()
			.model(b.model())
			.messages(new ArrayList<>(this.messages));
		if (b.temperature() != null) {
			rb.temperature(b.temperature());
		}
		if (b.maxTokens() != null) {
			rb.maxTokens(b.maxTokens());
		}
		if (b.topP() != null) {
			rb.topP(b.topP());
		}
		if (b.stop() != null) {
			if (b.stop() instanceof String s) {
				rb.stop(s);
			} else if (b.stop() instanceof List<?> l) {
				@SuppressWarnings("unchecked")
				List<String> strs = (List<String>) l;
				rb.stop(strs);
			}
		}
		rb.stream(b.stream());
		if (b.user() != null) {
			rb.user(b.user());
		}
		if (b.tools() != null) {
			rb.tools(b.tools());
		}
		if (b.toolChoice() != null) {
			rb.toolChoice(b.toolChoice());
		}
		if (b.presencePenalty() != null) {
			rb.presencePenalty(b.presencePenalty());
		}
		if (b.frequencyPenalty() != null) {
			rb.frequencyPenalty(b.frequencyPenalty());
		}
		if (b.seed() != null) {
			rb.seed(b.seed());
		}
		if (b.responseFormat() != null) {
			rb.responseFormat(b.responseFormat());
		}
		if (b.reasoningEffort() != null) {
			rb.reasoningEffort(b.reasoningEffort());
		}
		if (b.thinkingConfig() != null) {
			rb.thinkingConfig(b.thinkingConfig());
		}
		if (b.grounding() != null) {
			rb.grounding(b.grounding());
		}
		b.extra().forEach(rb::extra);
		return rb.build();
	}
}
