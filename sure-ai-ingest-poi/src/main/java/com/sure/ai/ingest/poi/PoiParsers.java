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

import java.util.List;

import com.sure.ai.ingest.spi.DocumentParser;

/**
 * POI 解析器便捷工厂：一次性产出 DOCX/XLSX/PPTX 三个解析器，便于批量注册。
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * FileSystemLoader loader = FileSystemLoader.builder()
 *         .register(PoiParsers.docx())
 *         .register(PoiParsers.xlsx())
 *         .register(PoiParsers.pptx())
 *         .build();
 * }</pre>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class PoiParsers {

	private PoiParsers() {
	}

	/**
	 * DOCX 解析器。
	 *
	 * @return DOCX 解析器
	 */
	public static DocumentParser docx() {
		return new DocxDocumentParser();
	}

	/**
	 * XLSX 解析器。
	 *
	 * @return XLSX 解析器
	 */
	public static DocumentParser xlsx() {
		return new XlsxDocumentParser();
	}

	/**
	 * PPTX 解析器。
	 *
	 * @return PPTX 解析器
	 */
	public static DocumentParser pptx() {
		return new PptxDocumentParser();
	}

	/**
	 * 全部 POI 解析器（DOCX/XLSX/PPTX）。
	 *
	 * @return 解析器列表
	 */
	public static List<DocumentParser> all() {
		return List.of(docx(), xlsx(), pptx());
	}
}
