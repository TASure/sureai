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

package com.sure.ai.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.splitter.FixedSizeTextSplitter;

/**
 * 知识库问答（RAG）完整示例。
 *
 * <p>端到端演示：读取本地纯文本文档 → {@link FixedSizeTextSplitter} 固定大小分块 →
 * 写入进程内向量库（{@code InMemoryVectorStore}）→ 语义检索 topK 上下文 →
 * 拼入系统提示词 → 调用对话模型生成答案。</p>
 *
 * <p><b>双模式（可离线跑通）：</b></p>
 * <ul>
 *   <li>检测到 {@code SURE_AI_OPENAI_API_KEY}：用真实 OpenAI 客户端同时承担对话与向量化；</li>
 *   <li>未检测到：自动降级为离线 Fake 实现——{@link OfflineEmbeddingClient} 输出字符哈希
 *       确定性向量、{@link OfflineChatClient} 把检索到的上下文直接回显为答案，
 *       全程零真实网络，用于本地开发与 CI 冒烟。</li>
 * </ul>
 *
 * <p>用法：{@code java com.sure.ai.examples.KnowledgeBaseDemo [可选:本地txt路径]}。
 * 不传文件时使用内置样例知识库。</p>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class KnowledgeBaseDemo {

	private KnowledgeBaseDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args args[0] 可选：本地 UTF-8 纯文本文档路径；缺省用内置样例
	 */
	public static void main(String[] args) {
		String documentText = resolveDocument(args);
		String question = "sureai 支持哪些核心能力？";

		boolean real = hasOpenAiKey();
		AiClient chatClient;
		EmbeddingClient embeddingClient;
		String chatModel;
		String embeddingModel;

		if (real) {
			System.out.println("[KnowledgeBaseDemo] 检测到 SURE_AI_OPENAI_API_KEY，使用真实 OpenAI 模式。");
			OpenAiClient openAi = new OpenAiClient(AiConfig.builder()
					.apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());
			chatClient = openAi;
			embeddingClient = openAi;
			chatModel = OpenAiModels.GPT_4O_MINI;
			embeddingModel = OpenAiModels.TEXT_EMBEDDING_3_SMALL;
		} else {
			System.out.println("[KnowledgeBaseDemo] 未检测到 Key，使用离线 Fake 模式（零网络）。");
			chatClient = new OfflineChatClient();
			embeddingClient = new OfflineEmbeddingClient();
			chatModel = "offline-chat-model";
			embeddingModel = "offline-embed-model";
		}

		// 组装管线：固定大小分块（块 120 字符、重叠 20）+ 进程内向量库 + 默认 topK=3
		RagPipeline pipeline = RagPipeline.builder()
				.chatClient(chatClient)
				.embeddingClient(embeddingClient)
				.chatModel(chatModel)
				.embeddingModel(embeddingModel)
				.splitter(new FixedSizeTextSplitter(120, 20))
				.defaultTopK(3)
				.build();

		System.out.println();
		System.out.println("=== 1. 摄入本地文档 ===");
		int chunkCount = pipeline.ingest("knowledge-base", documentText);
		System.out.println("  文档块数：" + chunkCount + "，向量库规模：" + pipeline.vectorStore().size());

		System.out.println();
		System.out.println("=== 2. 检索增强（语义召回 topK）===");
		List<Document> hits = pipeline.retrieve(question, 3);
		for (int i = 0; i < hits.size(); i++) {
			Document doc = hits.get(i);
			System.out.println("  [" + (i + 1) + "] " + doc.id() + " → " + doc.text());
		}

		System.out.println();
		System.out.println("=== 3. 检索增强生成（RAG ask）===");
		System.out.println("  问题：" + question);
		ChatResponse answer = pipeline.ask(question, 3);
		System.out.println("  答案：" + answer.firstText());

		chatClient.close();
		System.out.println();
		System.out.println("KnowledgeBaseDemo 完成。");
	}

	/** 解析文档来源：命令行给路径则读本地文件，否则用内置样例。 */
	private static String resolveDocument(String[] args) {
		if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
			Path path = Path.of(args[0]);
			try {
				String text = Files.readString(path);
				System.out.println("[KnowledgeBaseDemo] 已加载本地文档：" + path.toAbsolutePath());
				return text;
			} catch (IOException e) {
				System.out.println("[KnowledgeBaseDemo] 读取文档失败（" + e.getMessage()
						+ "），回退到内置样例。");
			}
		}
		return "sureai 是一个零第三方依赖的 Java 大模型接入工具库。"
				+ "每个主流 AI 平台对应一个独立 Maven 模块与静态入口工具类。"
				+ "sureai 支持流式对话、Function Calling、Embedding 与 RAG 检索增强问答。"
				+ "sureai 提供 AI Gateway 多供应商统一路由与故障转移，内置 ReActAgent 多工具编排。"
				+ "sureai 全模块可 GraalVM native-image 编译为本地可执行文件，运行期无 JVM 依赖。";
	}

	private static boolean hasOpenAiKey() {
		String key = System.getenv("SURE_AI_OPENAI_API_KEY");
		return key != null && !key.isBlank();
	}

	/** 离线对话模型：把检索上下文中的首条命中文档回显为答案，验证「检索→生成」接线。 */
	private static final class OfflineChatClient implements AiClient {
		@Override
		public String name() {
			return "offline-rag-chat";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String context = "";
			for (ChatMessage message : request.messages()) {
				if (message.role() == Role.SYSTEM && message.content() != null) {
					context = message.content();
				}
			}
			String answer = "【离线生成】根据检索到的知识库上下文回答。命中片段：" + firstHit(context);
			ChatMessage out = ChatMessage.assistant(answer);
			return ChatResponse.of("offline-rag-resp", "offline-chat-model",
					List.of(Choice.of(0, out, "stop")),
					TokenUsage.of(0, 0, 0), null);
		}

		private static String firstHit(String context) {
			int idx = context.indexOf("[1] ");
			if (idx < 0) {
				return context.isBlank() ? "（无上下文命中）" : context;
			}
			String tail = context.substring(idx + 4);
			int end = tail.indexOf('\n');
			return end < 0 ? tail : tail.substring(0, end);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}

	/** 离线向量化客户端：按字符哈希把文本映射为固定维度向量（字符重叠越多余弦越近）。 */
	private static final class OfflineEmbeddingClient implements EmbeddingClient {
		private static final int DIM = 64;

		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			List<float[]> vectors = new java.util.ArrayList<>(request.input().size());
			for (String text : request.input()) {
				vectors.add(embedText(text));
			}
			return EmbeddingResponse.of("offline-embed-model", vectors, TokenUsage.of(0, 0, 0));
		}

		private static float[] embedText(String text) {
			float[] vec = new float[DIM];
			String safe = text == null ? "" : text;
			for (int i = 0; i < safe.length(); i++) {
				int bucket = Math.abs(Character.hashCode(safe.charAt(i)) % DIM);
				vec[bucket] += 1.0f;
			}
			double norm = 0.0;
			for (float v : vec) {
				norm += (double) v * v;
			}
			norm = Math.sqrt(norm);
			if (norm > 0) {
				for (int i = 0; i < DIM; i++) {
					vec[i] /= (float) norm;
				}
			}
			return vec;
		}
	}
}
