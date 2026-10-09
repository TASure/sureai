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

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import com.sure.ai.ingest.DocumentLoader;
import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * PPTX 文档解析器（Apache POI）：逐幻灯片抽取文本框中的段落文本。
 *
 * <p>需工程自行声明 {@code org.apache.poi:poi-ooxml} 依赖（provided 不传递）。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class PptxDocumentParser implements DocumentParser {

	/** 支持的扩展名。 */
	private static final Set<String> EXTENSIONS = Set.of(".pptx");

	@Override
	public Set<String> extensions() {
		return EXTENSIONS;
	}

	@Override
	public List<Document> parse(byte[] content, String source, Map<String, String> metadata) {
		StringBuilder sb = new StringBuilder();
		try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(content))) {
			for (XSLFSlide slide : show.getSlides()) {
				for (XSLFShape shape : slide.getShapes()) {
					if (shape instanceof XSLFTextShape textShape) {
						for (XSLFTextParagraph paragraph : textShape.getTextParagraphs()) {
							String line = paragraph.getText();
							if (line != null && !line.isBlank()) {
								sb.append(line).append('\n');
							}
						}
					}
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("解析 PPTX 失败: " + source, e);
		}
		String text = sb.toString().trim();
		if (text.isEmpty()) {
			return new ArrayList<>(0);
		}
		metadata.put(DocumentLoader.META_FORMAT, "pptx");
		List<Document> documents = new ArrayList<>(1);
		documents.add(Document.of(source, text, metadata));
		return documents;
	}
}
