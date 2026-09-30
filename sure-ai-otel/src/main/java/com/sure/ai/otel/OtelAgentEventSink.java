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

package com.sure.ai.otel;

import com.sure.ai.agent.event.AgentErrorEvent;
import com.sure.ai.agent.event.AgentEvent;
import com.sure.ai.agent.event.AgentEventSink;
import com.sure.ai.agent.event.FinalAnswerEvent;
import com.sure.ai.agent.event.StepCompletedEvent;
import com.sure.ai.agent.event.StepStartedEvent;
import com.sure.ai.agent.event.ThoughtEvent;
import com.sure.ai.agent.event.ToolCalledEvent;
import com.sure.ai.agent.event.ToolCompletedEvent;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

/**
 * 把 sureai {@link AgentEventSink} 事件流桥接为 OpenTelemetry span 事件的订阅者。
 *
 * <p>每个 {@link AgentEvent}（thought / tool.called / tool.completed / step.* / final.answer /
 * agent.error）被记录为<b>当前 span</b>（{@link Span#current()}）上的一个带时间戳的事件，
 * 事件名即 {@link AgentEvent#type()}，并附带 {@code agent.id}（与错误时的 {@code error.type}）。
 * 这样 Langfuse/Jaeger/Tempo 在观察一次 Agent 调用时，能在 trace 上看到完整的思考/工具/步骤时间线。</p>
 *
 * <p><b>边界与无感降级</b>：本桥接只写「事件」，不自行创建/结束 span——span 生命周期由使用方
 * （或 OTel agent）管理。当没有正在录制的 span（{@code Span.current().isRecording()==false}，
 * 例如未接入 tracing 或调用不在 trace 上下文中）时直接跳过，零副作用。未引入 OTel SDK 时本类不被加载。</p>
 *
 * <p>接入：{@code publisher.subscribe(new OtelAgentEventSink())}（配合 StreamingAgentListener 产生事件）。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class OtelAgentEventSink implements AgentEventSink {

	/** Agent 标识扩展属性键。 */
	static final String ATTR_AGENT_ID = "agent.id";

	@Override
	public void onEvent(AgentEvent event) {
		if (event == null) {
			return;
		}
		Span span = Span.current();
		if (span == null || !span.isRecording()) {
			return;
		}
		AttributesBuilder b = new AttributesBuilder(event);
		Attributes attrs = b.build();
		// addEvent(String, Attributes) 以当前时间打戳；事件自身的 timestampEpochMs 作为信息保留在事件名体系外
		span.addEvent(event.type(), attrs);
	}

	/** 按事件类型抽取 agent.id 与可选 error.type。 */
	private static final class AttributesBuilder {
		private final io.opentelemetry.api.common.AttributesBuilder delegate = Attributes.builder();

		AttributesBuilder(AgentEvent event) {
			String agentId = agentIdOf(event);
			if (agentId != null && !agentId.isBlank()) {
				this.delegate.put(ATTR_AGENT_ID, agentId);
			}
			if (event instanceof AgentErrorEvent err && err.error() != null && !err.error().isBlank()) {
				this.delegate.put(OtelGenAiMetrics.ATTR_ERROR_TYPE, err.error());
			}
		}

		Attributes build() {
			return this.delegate.build();
		}
	}

	/** 从具体事件 record 中取 agentId（基接口未定义该访问器）。 */
	private static String agentIdOf(AgentEvent event) {
		if (event instanceof ThoughtEvent e) {
			return e.agentId();
		}
		if (event instanceof ToolCalledEvent e) {
			return e.agentId();
		}
		if (event instanceof ToolCompletedEvent e) {
			return e.agentId();
		}
		if (event instanceof StepStartedEvent e) {
			return e.agentId();
		}
		if (event instanceof StepCompletedEvent e) {
			return e.agentId();
		}
		if (event instanceof FinalAnswerEvent e) {
			return e.agentId();
		}
		if (event instanceof AgentErrorEvent e) {
			return e.agentId();
		}
		return null;
	}
}
