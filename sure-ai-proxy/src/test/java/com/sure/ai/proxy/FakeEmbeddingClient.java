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
package com.sure.ai.proxy;

import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.TokenUsage;

/**
 * 测试用 {@link EmbeddingClient}（同时实现 {@link AiClient} 以便注册进 {@code ClientRegistry}）。
 *
 * @author sureai
 * @since 1.6.0
 */
final class FakeEmbeddingClient implements AiClient, EmbeddingClient {

	/** 最近一次 embed 的 model。 */
	private volatile String lastModel;

	@Override
	public String name() {
		return "fake-embed";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		throw new UnsupportedOperationException("not needed");
	}

	@Override
	public void chatStream(ChatRequest request, java.util.function.Consumer<ChatStreamChunk> consumer) {
		throw new UnsupportedOperationException("not needed");
	}

	@Override
	public void close() {
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		this.lastModel = request.model();
		float[] vec = new float[] { 0.1f, 0.2f, 0.3f };
		return EmbeddingResponse.of(request.model(),
				List.of(vec), TokenUsage.of(5, 0, 5));
	}

	/** 最近一次 embed 的 model。 */
	String lastModel() {
		return this.lastModel;
	}
}
