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

package com.sure.ai.rag.embedding;

import java.util.List;

/**
 * 文本向量化抽象：将文本转换为嵌入向量。
 *
 * <p>屏蔽底层 {@code EmbeddingClient} 的具体实现（OpenAI/通义/智谱等均可），
 * 便于在 {@code RagPipeline} 中统一使用。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface EmbeddingProvider {

	/**
	 * 将单条文本向量化。
	 *
	 * @param text 文本，可为 null（按空文本处理）
	 * @return 嵌入向量
	 */
	float[] embed(String text);

	/**
	 * 批量向量化。
	 *
	 * <p>实现应尽量复用底层平台的批量接口以降低调用次数与成本。</p>
	 *
	 * @param texts 文本列表，可为空
	 * @return 与输入一一对应的向量列表
	 */
	List<float[]> embedAll(List<String> texts);
}
