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

package com.sure.ai.agent.event;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolCall;
import com.sure.tool.lang.Assert;

/**
 * 把同步 {@link AgentListener} 回调桥接为 {@link AgentEvent} 并投递到事件 sink。
 *
 * <p>映射关系：</p>
 * <ul>
 *   <li>{@code onThought} → {@link ThoughtEvent}；</li>
 *   <li>{@code onToolCall} → {@link ToolCalledEvent}（argumentsJson 解析为对象，非法时空对象）；</li>
 *   <li>{@code onToolResult} → {@link ToolCompletedEvent}（success 由错误前缀判定）；</li>
 *   <li>{@code onFinish} → {@link FinalAnswerEvent}；</li>
 *   <li>{@code onError} → {@link AgentErrorEvent}；</li>
 *   <li>{@code onStepStart/onStepComplete} → {@link StepStartedEvent}/{@link StepCompletedEvent}。</li>
 * </ul>
 *
 * <p>{@link TokenDeltaEvent} 不在桥接中产生（ReActAgent 走非流式 chat），为将来流式接入预留。
 * 用法：把本监听器传给 ReActAgent，订阅 {@link AgentEventPublisher} 即可获得事件流，
 * 无需改动 ReActAgent。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class StreamingAgentListener implements AgentListener {

	private final String agentId;
	private final AgentEventSink sink;

	/**
	 * 构造。
	 *
	 * @param agentId 编排器标识（写入每个事件）
	 * @param sink    事件接收方
	 */
	public StreamingAgentListener(String agentId, AgentEventSink sink) {
		Assert.notBlank(agentId, "agentId must not be blank");
		Assert.notNull(sink, "sink must not be null");
		this.agentId = agentId;
		this.sink = sink;
	}

	/**
	 * 便捷构造：以广播器为接收方。
	 *
	 * @param agentId  编排器标识
	 * @param publisher 广播器
	 */
	public StreamingAgentListener(String agentId, AgentEventPublisher publisher) {
		this(agentId, (AgentEventSink) publisher);
	}

	@Override
	public void onThought(String thought) {
		this.sink.onEvent(new ThoughtEvent(this.agentId, thought, System.currentTimeMillis()));
	}

	@Override
	public void onToolCall(ToolCall toolCall) {
		JsonObject args = parseArguments(toolCall.argumentsJson());
		this.sink.onEvent(new ToolCalledEvent(this.agentId, toolCall.name(), args,
			System.currentTimeMillis()));
	}

	@Override
	public void onToolResult(ToolCall toolCall, String result) {
		this.sink.onEvent(new ToolCompletedEvent(this.agentId, toolCall.name(), result,
			!isToolError(result), System.currentTimeMillis()));
	}

	@Override
	public void onFinish(String finalAnswer) {
		this.sink.onEvent(new FinalAnswerEvent(this.agentId, finalAnswer,
			System.currentTimeMillis()));
	}

	@Override
	public void onError(Throwable error) {
		String type = error == null ? "Unknown" : error.getClass().getSimpleName();
		String message = error == null ? null : error.getMessage();
		this.sink.onEvent(new AgentErrorEvent(this.agentId, type, message,
			System.currentTimeMillis()));
	}

	@Override
	public void onStepStart(int index, String step) {
		this.sink.onEvent(new StepStartedEvent(this.agentId, index, step,
			System.currentTimeMillis()));
	}

	@Override
	public void onStepComplete(int index, String result) {
		this.sink.onEvent(new StepCompletedEvent(this.agentId, index, result,
			System.currentTimeMillis()));
	}

	/** 判断工具回灌文本是否为错误（与 ReActAgent 错误前缀保持一致）。 */
	static boolean isToolError(String result) {
		if (result == null) {
			return false;
		}
		return result.startsWith("参数校验失败")
			|| result.startsWith("tool not found")
			|| result.startsWith("工具执行异常")
			|| result.startsWith("arguments 不是合法 JSON 对象");
	}

	/** 解析 argumentsJson 为对象，非法时返回空对象。 */
	private static JsonObject parseArguments(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return Json.object();
		}
		try {
			JsonElement el = Json.parse(argumentsJson);
			if (el != null && el.isObject()) {
				return el.getAsJsonObject();
			}
		} catch (RuntimeException ignored) {
			// 落到空对象
		}
		return Json.object();
	}
}
