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
 * 长期记忆子包：跨会话的记忆沉淀与召回。
 *
 * <p>与 {@link com.sure.ai.agent.memory.ConversationMemory}（会话内短期历史）互补：
 * 长期记忆通过 {@link com.sure.ai.agent.memory.longterm.LongTermMemory} 在多轮对话间
 * 持久化用户偏好、关键事实与最终结论，并在下次运行前按相关性召回注入上下文。</p>
 *
 * <p>存储抽象为 {@link com.sure.ai.agent.memory.longterm.MemoryStore} /
 * {@link com.sure.ai.agent.memory.longterm.VectorMemoryStore}，内置并发安全的内存实现；
 * 提取、向量化、摘要均为可插拔 SPI，零新依赖。</p>
 */
package com.sure.ai.agent.memory.longterm;
