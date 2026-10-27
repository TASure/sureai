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

package {{mainPackage}};

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.KeywordRetriever;
import com.sure.ai.rag.splitter.FixedSizeTextSplitter;

/**
 * 知识库问答模板（RAG 端到端）。
 *
 * <p>离线演示（无需 API key）：本地文档 → 固定大小分块 → 关键词检索 Top-K；
 * 设置 {@code SURE_AI_OPENAI_API_KEY} 后追加把检索结果拼入上下文生成答案。</p>
 *
 * @author sureai
 */
public final class Main {

	/** 内置示例文档（无参数运行时使用）。 */
	private static final String SAMPLE_DOC = """
		知识库问答（RAG）流程分四步：第一步加载文档，第二步切分文本，
		第三步把分块写入向量存储并建立索引，第四步检索相关片段拼入提示词后交给大模型生成。
		关键词检索（BM25）不依赖嵌入模型，适合快速离线验证。
		""";

	private Main() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口。
	 *
	 * @param args 可选参数 [0]=本地文档路径；缺省使用内置示例
	 */
	public static void main(String[] args) throws Exception {
		String text = args.length > 0 ? Files.readString(Path.of(args[0])) : SAMPLE_DOC;

		// 1) 分块
		FixedSizeTextSplitter splitter = new FixedSizeTextSplitter(120, 20);
		List<String> chunks = splitter.split(text);
		System.out.println("分块数：" + chunks.size());

		// 2) 建立索引
		List<Document> docs = new java.util.ArrayList<>();
		for (int i = 0; i < chunks.size(); i++) {
			docs.add(Document.of("chunk-" + i, chunks.get(i)));
		}
		KeywordRetriever retriever = new KeywordRetriever(docs);

		// 3) 检索
		String query = "检索和生成有什么顺序";
		List<KeywordRetriever.Scored> hits = retriever.retrieveWithScores(query, 2);
		System.out.println("检索 Top-" + hits.size() + "（query=" + query + "）：");
		StringBuilder context = new StringBuilder();
		for (KeywordRetriever.Scored hit : hits) {
			System.out.println("  [" + hit.score() + "] " + truncate(hit.document().text(), 40));
			context.append(hit.document().text()).append('\n');
		}

		// 4) 生成（可选，需 API key）
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("未设置 SURE_AI_OPENAI_API_KEY，已演示离线检索；设置后追加生成环节。");
			return;
		}
		ChatRequest request = ChatRequest.builder()
			.model(OpenAiModels.GPT_4O_MINI)
			.messages(List.of(
				ChatMessage.system("你是知识库问答助手，只依据给定上下文回答。"),
				ChatMessage.user("上下文：\n" + context + "\n问题：" + query)))
			.build();
		ChatResponse response = OpenAiUtil.chat(request);
		System.out.println("生成答案：" + response.firstText());
	}

	private static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "...";
	}
}
