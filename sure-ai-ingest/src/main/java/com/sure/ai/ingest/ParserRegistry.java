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

package com.sure.ai.ingest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.sure.ai.ingest.parser.HtmlTextParser;
import com.sure.ai.ingest.parser.PdfTextParser;
import com.sure.ai.ingest.parser.PlainTextParser;
import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * 格式路由注册表：扩展名 → {@link DocumentParser}。包私有，供两个加载器复用。
 *
 * @author sureai
 * @since 2.6.0
 */
final class ParserRegistry {

	private final Map<String, DocumentParser> byExtension = new TreeMap<>();

	/**
	 * 注册一个解析器的全部扩展名。
	 *
	 * @param parser 解析器
	 */
	void register(DocumentParser parser) {
		for (String ext : parser.extensions()) {
			byExtension.put(normalize(ext), parser);
		}
	}

	/**
	 * 用文件名查解析器，找不到返回 null。
	 *
	 * @param filename 文件名或路径末段
	 * @return 解析器或 null
	 */
	DocumentParser parserFor(String filename) {
		String ext = extensionOf(filename);
		return ext == null ? null : byExtension.get(ext);
	}

	/**
	 * 路由：按扩展名选解析器并产出文档列表。
	 *
	 * @param content 字节
	 * @param filename 文件名 / 扩展名来源
	 * @param source 来源标识
	 * @param contentType HTTP Content-Type（可空）
	 * @return 文档列表
	 */
	java.util.List<Document> route(byte[] content, String filename, String source, String contentType) {
		DocumentParser parser = parserFor(filename);
		if (parser == null) {
			throw new IllegalStateException("不支持的文件格式: " + source
					+ (filename == null || filename.isEmpty() ? "" : "（" + filename + "）"));
		}
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(DocumentLoader.META_SOURCE, source);
		metadata.put(DocumentLoader.META_LOADED_AT, Instant.now().toString());
		if (contentType != null && !contentType.isEmpty()) {
			metadata.put(DocumentLoader.META_CONTENT_TYPE, contentType);
		}
		return parser.parse(content, source, metadata);
	}

	/**
	 * 创建含内置纯文本族与 PDF 的默认注册表。
	 *
	 * @return 默认注册表
	 */
	static ParserRegistry withDefaults() {
		ParserRegistry registry = new ParserRegistry();
		registry.register(new PlainTextParser());
		registry.register(new HtmlTextParser());
		registry.register(new PdfTextParser());
		return registry;
	}

	/**
	 * 归一化扩展名：小写、补前导点。
	 *
	 * @param ext 扩展名
	 * @return 归一化扩展名
	 */
	private static String normalize(String ext) {
		String e = ext.toLowerCase();
		if (!e.startsWith(".")) {
			e = "." + e;
		}
		return e;
	}

	/**
	 * 取文件名扩展名（小写、含点），无扩展名返回 null。
	 *
	 * @param filename 文件名
	 * @return 扩展名或 null
	 */
	static String extensionOf(String filename) {
		if (filename == null) {
			return null;
		}
		int dot = filename.lastIndexOf('.');
		if (dot < 0 || dot == filename.length() - 1) {
			return null;
		}
		return filename.substring(dot).toLowerCase();
	}

	/**
	 * 按 Content-Type 推断扩展名（文件名无后缀时的兜底）。
	 *
	 * @param contentType 响应 Content-Type
	 * @return 扩展名，无法推断返回 null
	 */
	static String extensionByContentType(String contentType) {
		if (contentType == null) {
			return null;
		}
		String ct = contentType.toLowerCase();
		if (ct.contains("html")) {
			return ".html";
		}
		if (ct.contains("pdf")) {
			return ".pdf";
		}
		if (ct.contains("markdown")) {
			return ".md";
		}
		if (ct.contains("text/plain")) {
			return ".txt";
		}
		return null;
	}
}
