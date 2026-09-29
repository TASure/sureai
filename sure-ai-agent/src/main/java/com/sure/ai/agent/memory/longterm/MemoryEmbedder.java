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

package com.sure.ai.agent.memory.longterm;

/**
 * 文本向量化器：把一段文本映射为稠密向量。
 *
 * <p>无外部嵌入模型时使用 {@link NoopMemoryEmbedder}，检索自动降级为文本匹配。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
@FunctionalInterface
public interface MemoryEmbedder {

	/**
	 * 把文本转向量。
	 *
	 * @param text 文本（非 null）
	 * @return 向量；返回 null 表示不向量化（降级为文本检索）
	 */
	float[] embed(String text);
}
