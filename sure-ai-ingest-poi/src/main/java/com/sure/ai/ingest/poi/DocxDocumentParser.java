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

package com.sure.ai.ingest.poi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import com.sure.ai.ingest.DocumentLoader;
import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * DOCX 文档解析器（Apache POI）：提取 Word 段落与表格文本为单条 {@link Document}。
 *
 * <p>需工程自行声明 {@code org.apache.poi:poi-ooxml} 依赖（provided 不传递）。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class DocxDocumentParser implements DocumentParser {

	/** 支持的扩展名。 */
	private static final Set<String> EXTENSIONS = Set.of(".docx");

	@Override
	public Set<String> extensions() {
		return EXTENSIONS;
	}

	@Override
	public List<Document> parse(byte[] content, String source, Map<String, String> metadata) {
		String text;
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
				XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
			text = extractor.getText();
		} catch (IOException e) {
			throw new IllegalStateException("解析 DOCX 失败: " + source, e);
		}
		if (text == null) {
			text = "";
		}
		text = text.trim();
		if (text.isEmpty()) {
			return new ArrayList<>(0);
		}
		metadata.put(DocumentLoader.META_FORMAT, "docx");
		List<Document> documents = new ArrayList<>(1);
		documents.add(Document.of(source, text, metadata));
		return documents;
	}
}
