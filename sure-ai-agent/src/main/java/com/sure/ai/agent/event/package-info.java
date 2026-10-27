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

/**
 * Agent 流式事件子包：把编排执行过程以类型化事件流对外暴露。
 *
 * <p>提供 {@link com.sure.ai.agent.event.AgentEvent} 标记接口与一组不可变 record
 * （步骤/工具/思考/最终答案/错误/token 增量），{@link com.sure.ai.agent.event.AgentEventPublisher}
 * 多订阅者广播，{@link com.sure.ai.agent.event.StreamingAgentListener} 把同步
 * {@code AgentListener} 回调桥接为事件，以及 {@link com.sure.ai.agent.event.AgentEventSseWriter}
 * 输出 SSE 帧。ReActAgent 无需改动，传入桥接监听器即可获得事件流。</p>
 */
package com.sure.ai.agent.event;
