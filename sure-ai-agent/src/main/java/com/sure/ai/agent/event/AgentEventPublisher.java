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

import java.util.concurrent.CopyOnWriteArrayList;

import com.sure.tool.lang.Assert;

/**
 * 线程安全的多订阅者事件广播器。
 *
 * <p>内部使用 {@link CopyOnWriteArrayList}：发布无锁，订阅变更为写时复制，
 * 适合「发布频次高、订阅者少、订阅变更不频繁」的 Agent 事件流场景。
 * 发布时按订阅顺序同步遍历调用单个订阅者；单个订阅者抛异常会被隔离，
 * 不影响其余订阅者。</p>
 *
 * <p>同时实现 {@link AgentEventSink}，可直接作为桥接器的目标 sink。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class AgentEventPublisher implements AgentEventSink {

	private final CopyOnWriteArrayList<AgentEventSink> sinks = new CopyOnWriteArrayList<>();

	/**
	 * 订阅事件。
	 *
	 * @param sink 订阅者
	 */
	public void subscribe(AgentEventSink sink) {
		Assert.notNull(sink, "sink must not be null");
		this.sinks.addIfAbsent(sink);
	}

	/**
	 * 取消订阅。
	 *
	 * @param sink 订阅者
	 */
	public void unsubscribe(AgentEventSink sink) {
		Assert.notNull(sink, "sink must not be null");
		this.sinks.remove(sink);
	}

	/**
	 * 广播事件给全部订阅者。
	 *
	 * @param event 事件
	 */
	public void publish(AgentEvent event) {
		Assert.notNull(event, "event must not be null");
		for (AgentEventSink sink : this.sinks) {
			try {
				sink.onEvent(event);
			} catch (RuntimeException ignored) {
				// 单个订阅者异常隔离，不影响其余订阅者
			}
		}
	}

	/**
	 * 清空全部订阅者。
	 */
	public void clear() {
		this.sinks.clear();
	}

	/**
	 * 当前订阅者数量（主要用于测试）。
	 *
	 * @return 数量
	 */
	public int subscriberCount() {
		return this.sinks.size();
	}

	@Override
	public void onEvent(AgentEvent event) {
		publish(event);
	}
}
