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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.tool.lang.Assert;

/**
 * 基于 {@link EmbeddingClient} 的向量化实现。
 *
 * <p>将 sure-ai-core 的通用嵌入客户端适配为 {@link EmbeddingProvider}，
 * 与具体平台解耦：OpenAI、通义、智谱、Gemini、豆包、百度等平台的
 * Embedding 客户端均可直接传入。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class ClientEmbeddingProvider implements EmbeddingProvider {

	private final EmbeddingClient client;
	private final String model;

	/**
	 * 创建客户端向量化实现。
	 *
	 * @param client 嵌入客户端
	 * @param model 嵌入模型 ID（如 text-embedding-3-small、text-embedding-v3）
	 */
	public ClientEmbeddingProvider(EmbeddingClient client, String model) {
		this.client = Assert.notNull(client, "client 不能为 null");
		this.model = Assert.notBlank(model, "model 不能为空");
	}

	/**
	 * 返回底层嵌入客户端。
	 *
	 * @return 嵌入客户端
	 */
	public EmbeddingClient client() {
		return client;
	}

	/**
	 * 返回嵌入模型 ID。
	 *
	 * @return 模型 ID
	 */
	public String model() {
		return model;
	}

	@Override
	public float[] embed(String text) {
		EmbeddingResponse response = client.embed(model, text == null ? "" : text);
		List<float[]> embeddings = response.embeddings();
		Assert.isTrue(embeddings != null && !embeddings.isEmpty(),
				"嵌入响应缺少向量数据，model={}", model);
		return embeddings.get(0).clone();
	}

	@Override
	public List<float[]> embedAll(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> normalized = new ArrayList<>(texts.size());
		for (String text : texts) {
			normalized.add(text == null ? "" : text);
		}
		EmbeddingResponse response = client.embed(new EmbeddingRequest(model, normalized));
		List<float[]> embeddings = response.embeddings();
		Assert.isTrue(embeddings != null && embeddings.size() == normalized.size(),
				"嵌入响应数量与输入不一致，期望 {} 实际 {}",
				normalized.size(), embeddings == null ? 0 : embeddings.size());
		List<float[]> result = new ArrayList<>(embeddings.size());
		for (float[] embedding : embeddings) {
			result.add(embedding.clone());
		}
		return result;
	}
}
