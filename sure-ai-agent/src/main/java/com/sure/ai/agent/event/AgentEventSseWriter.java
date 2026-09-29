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

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.util.JsonMapper;
import com.sure.tool.lang.Assert;

/**
 * 把 {@link AgentEvent} 序列化为 SSE（Server-Sent Events）文本帧。
 *
 * <p>输出格式遵循 SSE 规范：</p>
 * <pre>
 * event: &lt;type&gt;\n
 * data: &lt;json&gt;\n
 * \n
 * </pre>
 *
 * <p>其中 {@code data} 为事件 record 全部字段（含 {@code type} 与 {@code timestampEpochMs}）
 * 的 JSON。结束流使用 {@link #doneMarker()} 输出 {@code data: [DONE]}。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class AgentEventSseWriter {

	private AgentEventSseWriter() {
		throw new AssertionError("No instances");
	}

	/**
	 * 把事件序列化为一帧 SSE。
	 *
	 * @param event 事件
	 * @return SSE 文本（以空行结尾）
	 */
	public static String toSse(AgentEvent event) {
		Assert.notNull(event, "event must not be null");
		JsonObject jo = JsonMapper.toJsonObject(event);
		jo.put("type", event.type());
		return "event: " + event.type() + "\n"
			+ "data: " + Json.stringify(jo) + "\n"
			+ "\n";
	}

	/**
	 * 流结束标记。
	 *
	 * @return {@code "data: [DONE]\n\n"}
	 */
	public static String doneMarker() {
		return "data: [DONE]\n\n";
	}
}
