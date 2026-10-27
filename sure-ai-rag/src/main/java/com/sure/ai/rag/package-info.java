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
 * RAG（检索增强生成）能力包。
 *
 * <p>提供端到端检索增强生成管线：</p>
 * <ul>
 *   <li>{@code splitter}：文本分块（递归字符分块，支持块间重叠）；</li>
 *   <li>{@code store}：向量存储抽象与进程内实现（余弦相似度）；</li>
 *   <li>{@code embedding}：文本向量化抽象，适配 sure-ai-core 的 {@code EmbeddingClient}；</li>
 *   <li>{@code retriever}：检索器抽象与向量检索实现；</li>
 *   <li>{@code pipeline}：{@code RagPipeline} 端到端编排（索引 → 检索 → 增强 → 生成）；</li>
 *   <li>{@code RagUtil}：静态入口工具类。</li>
 * </ul>
 *
 * <p>本模块仅依赖 sure-ai-core，与具体平台解耦：对话与向量化客户端可传入
 * sureai 任意平台模块的客户端实例（OpenAI、通义、智谱、Gemini 等）。</p>
 */
package com.sure.ai.rag;
