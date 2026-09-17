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

package com.sure.ai.internal.http;

/**
 * SSE 事件记录。
 *
 * @param event 事件名，缺省为 "message"
 * @param data  事件数据（多行 data 以 \n 拼接）
 * @author sureai
 * @since 0.1.0
 */
public record SseEvent(String event, String data) {

	/** 默认事件名。 */
	public static final String DEFAULT_EVENT = "message";

	/**
	 * 紧凑构造。
	 *
	 * @param event 事件名，null 视为默认
	 * @param data  事件数据
	 */
	public SseEvent {
		if (event == null || event.isEmpty()) {
			event = DEFAULT_EVENT;
		}
	}
}
