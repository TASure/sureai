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
package com.sure.ai.evals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * 测试用脚本化向量客户端：按队列返回预设向量，零网络。
 */
final class ScriptedEmbeddingClient implements EmbeddingClient {

	private final List<float[]> vectors = new ArrayList<>();
	private int cursor;
	private RuntimeException error;

	ScriptedEmbeddingClient(float[]... vectors) {
		this.vectors.addAll(Arrays.asList(vectors));
	}

	ScriptedEmbeddingClient alwaysThrow(RuntimeException e) {
		this.error = e;
		return this;
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		if (this.error != null) {
			throw this.error;
		}
		List<float[]> out = new ArrayList<>();
		for (int i = 0; i < request.input().size(); i++) {
			if (this.cursor >= this.vectors.size()) {
				throw new IllegalStateException("脚本向量耗尽");
			}
			out.add(this.vectors.get(this.cursor++));
		}
		return EmbeddingResponse.of("embed-model", out, null);
	}
}
