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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * 文件系统加载器：读取本地文件，按扩展名路由到对应 {@link DocumentParser}。
 *
 * <p>默认注册 TXT/MD/HTML/PDF 解析器；DOCX/XLSX/PPTX 需配合可选模块
 * {@code sure-ai-ingest-poi}，通过 {@link Builder#register(DocumentParser)} 追加。</p>
 *
 * <p>{@link #load(URI)} 仅支持 {@code file:} scheme（转路径读取）；
 * 网络 URL 请用 {@link URLLoader}。文件不存在 / 读取失败 / 不支持的格式抛出
 * {@link IllegalStateException}；空文件 / 无文本层返回空列表。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class FileSystemLoader implements DocumentLoader {

	private final ParserRegistry registry;

	private FileSystemLoader(Builder builder) {
		this.registry = builder.registry;
	}

	/**
	 * 创建含内置解析器（TXT/MD/HTML/PDF）的默认加载器。
	 *
	 * @return 默认文件系统加载器
	 */
	public static FileSystemLoader createDefault() {
		return builder().build();
	}

	/**
	 * 创建 Builder，可追加自定义格式解析器。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public List<Document> load(Path path) {
		Assert.notNull(path, "path 不能为 null");
		if (!Files.exists(path)) {
			throw new IllegalStateException("文件不存在: " + path);
		}
		byte[] content;
		try {
			content = Files.readAllBytes(path);
		} catch (IOException e) {
			throw new IllegalStateException("读取文件失败: " + path, e);
		}
		Path fileName = path.getFileName();
		String filename = fileName == null ? path.toString() : fileName.toString();
		return registry.route(content, filename, path.toString(), null);
	}

	@Override
	public List<Document> load(URI uri) {
		Assert.notNull(uri, "uri 不能为 null");
		if ("file".equalsIgnoreCase(uri.getScheme())) {
			return load(Paths.get(uri));
		}
		throw new IllegalStateException(
				"FileSystemLoader 仅支持 file: URI，网络 URL 请用 URLLoader: " + uri);
	}

	/**
	 * {@link FileSystemLoader} 构造器。默认已注册 TXT/MD/HTML/PDF。
	 */
	public static final class Builder {

		private final ParserRegistry registry = ParserRegistry.withDefaults();

		private Builder() {
		}

		/**
		 * 追加一个格式解析器（如 POI 模块的 DOCX/XLSX/PPTX 解析器）。
		 *
		 * @param parser 解析器，不允许为 null
		 * @return this
		 */
		public Builder register(DocumentParser parser) {
			Assert.notNull(parser, "parser 不能为 null");
			registry.register(parser);
			return this;
		}

		/**
		 * 构建加载器。
		 *
		 * @return 文件系统加载器
		 */
		public FileSystemLoader build() {
			return new FileSystemLoader(this);
		}
	}
}
