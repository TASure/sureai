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

package com.sure.ai.ingest.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sure.ai.ingest.DocumentLoader;
import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * 纯文本解析器：按 UTF-8 把字节读为单条 {@link Document}。
 *
 * <p>同时覆盖 TXT 与 MD：Markdown 按「纯文本直读」处理——<b>不剥离标记符号</b>，
 * 保留标题/列表/代码块等原始文本，交由下游 rag 的 {@code MarkdownTextSplitter}
 * 做结构化切分。空内容返回空列表。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class PlainTextParser implements DocumentParser {

	/** 支持的扩展名。 */
	private static final Set<String> EXTENSIONS = Set.of(".txt", ".md", ".markdown");

	@Override
	public Set<String> extensions() {
		return EXTENSIONS;
	}

	@Override
	public List<Document> parse(byte[] content, String source, Map<String, String> metadata) {
		String text = new String(content, StandardCharsets.UTF_8).trim();
		if (text.isEmpty()) {
			return new ArrayList<>(0);
		}
		metadata.put(DocumentLoader.META_FORMAT, formatFromSource(source));
		List<Document> documents = new ArrayList<>(1);
		documents.add(Document.of(source, text, metadata));
		return documents;
	}

	/**
	 * 按来源扩展名推断格式标签。
	 *
	 * @param source 来源标识
	 * @return txt / md
	 */
	private static String formatFromSource(String source) {
		String lower = source.toLowerCase();
		if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
			return "md";
		}
		return "txt";
	}
}
