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

package com.sure.ai.rag;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.TokenUsage;

/**
 * 测试用确定性向量化客户端：按字符哈希生成 16 维归一化向量。
 *
 * <p>同一文本恒得同一向量（余弦相似度 1.0），保证检索测试可复现且不访问网络。</p>
 */
public final class TestEmbeddingClient implements EmbeddingClient {

	private static final int DIM = 16;

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		List<String> inputs = request.input();
		List<float[]> embeddings = new ArrayList<>(inputs.size());
		for (String input : inputs) {
			embeddings.add(hashEmbedding(input));
		}
		return EmbeddingResponse.of(request.model(), embeddings,
				TokenUsage.of(0, 0, 0));
	}

	/**
	 * 生成确定性归一化向量。
	 *
	 * @param text 文本
	 * @return 16 维归一化向量
	 */
	public static float[] hashEmbedding(String text) {
		float[] vector = new float[DIM];
		for (int i = 0; i < text.length(); i++) {
			vector[i % DIM] += text.charAt(i);
		}
		double norm = 0;
		for (float value : vector) {
			norm += (double) value * value;
		}
		norm = Math.sqrt(norm);
		if (norm > 0) {
			for (int i = 0; i < DIM; i++) {
				vector[i] = (float) (vector[i] / norm);
			}
		}
		return vector;
	}
}
