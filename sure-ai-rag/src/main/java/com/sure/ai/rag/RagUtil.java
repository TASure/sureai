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

import com.sure.ai.client.AiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.splitter.RecursiveCharacterTextSplitter;
import com.sure.ai.rag.splitter.TextSplitter;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;

/**
 * RAG（检索增强生成）静态入口工具类。
 *
 * <p>提供向量存储、文本分块器与检索增强管线等常用组件的快捷创建方法，
 * 与 sureai 各平台模块的静态工具类风格一致，开箱即用。</p>
 *
 * <p>典型用法：</p>
 * <pre>
 *   RagPipeline pipeline = RagUtil.pipeline(chatClient, embeddingClient,
 *           "gpt-4o-mini", "text-embedding-3-small");
 *   pipeline.ingest("doc-1", "公司成立于 2010 年……");
 *   ChatResponse answer = pipeline.ask("公司成立于哪一年？");
 * </pre>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class RagUtil {

	private RagUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 创建默认文本分块器（块大小 1000、重叠 200）。
	 *
	 * @return 文本分块器
	 */
	public static TextSplitter splitter() {
		return RecursiveCharacterTextSplitter.createDefault();
	}

	/**
	 * 创建自定义参数的文本分块器。
	 *
	 * @param chunkSize 块大小（字符数）
	 * @param chunkOverlap 块间重叠（字符数）
	 * @return 文本分块器
	 */
	public static TextSplitter splitter(int chunkSize, int chunkOverlap) {
		return new RecursiveCharacterTextSplitter(chunkSize, chunkOverlap, null, true);
	}

	/**
	 * 创建进程内向量存储。
	 *
	 * @return 向量存储
	 */
	public static VectorStore inMemoryStore() {
		return new InMemoryVectorStore();
	}

	/**
	 * 创建检索增强管线（默认进程内向量库 + 递归字符分块器 + 默认 topK=4）。
	 *
	 * @param chatClient 对话客户端（任意平台实例）
	 * @param embeddingClient 向量化客户端（任意平台实例）
	 * @param chatModel 对话模型 ID
	 * @param embeddingModel 向量化模型 ID
	 * @return 检索增强管线
	 */
	public static RagPipeline pipeline(AiClient chatClient, EmbeddingClient embeddingClient,
			String chatModel, String embeddingModel) {
		return RagPipeline.builder()
				.chatClient(chatClient)
				.embeddingClient(embeddingClient)
				.chatModel(chatModel)
				.embeddingModel(embeddingModel)
				.build();
	}

	/**
	 * 创建完全自定义的检索增强管线。
	 *
	 * @return 管线 Builder
	 */
	public static RagPipeline.Builder pipelineBuilder() {
		return RagPipeline.builder();
	}
}
