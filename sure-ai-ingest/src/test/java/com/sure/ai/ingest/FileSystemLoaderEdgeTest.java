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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * {@link FileSystemLoader} 边界测试：读取目录触发 IOException、Builder 注册自定义解析器与空参校验。
 */
public class FileSystemLoaderEdgeTest {

	/** 临时目录。 */
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	/**
	 * 读取目录：Files.readAllBytes 抛 IOException，包装为 IllegalStateException。
	 *
	 * @throws Exception 创建临时目录失败
	 */
	@Test
	public void testLoadDirectoryThrows() throws Exception {
		Path dir = temp.newFolder("sub").toPath();
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> FileSystemLoader.createDefault().load(dir));
		assertTrue(e.getMessage(), e.getMessage().contains("读取文件失败"));
	}

	/**
	 * Builder.register 追加自定义解析器后，可按其扩展名加载。
	 *
	 * @throws Exception 写临时文件失败
	 */
	@Test
	public void testBuilderRegisterCustomParser() throws Exception {
		DocumentParser custom = new MarkupParser();
		FileSystemLoader loader = FileSystemLoader.builder().register(custom).build();
		Path file = temp.newFile("note.markup").toPath();
		Files.writeString(file, "MARKUP-BODY", StandardCharsets.UTF_8);
		List<Document> docs = loader.load(file);
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("MARKUP-BODY"));
	}

	/**
	 * Builder.register(null) 触发空参校验。
	 */
	@Test
	public void testRegisterNullRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> FileSystemLoader.builder().register(null));
	}

	/**
	 * load(null) 路径触发空参校验。
	 */
	@Test
	public void testLoadNullPathRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> FileSystemLoader.createDefault().load((Path) null));
	}

	/**
	 * load((URI)null) 触发空参校验。
	 */
	@Test
	public void testLoadNullUriRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> FileSystemLoader.createDefault().load((java.net.URI) null));
	}

	/**
	 * createDefault 与 builder().build() 产物均含内置 txt 解析器。
	 *
	 * @throws Exception 写临时文件失败
	 */
	@Test
	public void testBuildersProduceUsableLoader() throws Exception {
		Path file = temp.newFile("b.txt").toPath();
		Files.writeString(file, "via-builder", StandardCharsets.UTF_8);
		FileSystemLoader loader = FileSystemLoader.builder().build();
		List<Document> docs = loader.load(file);
		assertEquals(1, docs.size());
		assertEquals("via-builder", docs.get(0).text());
		assertNotNull(docs.get(0).metadata().get(DocumentLoader.META_SOURCE));
	}

	/**
	 * 自定义 .markup 解析器（纯文本直读）。
	 */
	private static final class MarkupParser implements DocumentParser {

		@Override
		public java.util.Set<String> extensions() {
			return java.util.Set.of(".markup");
		}

		@Override
		public List<Document> parse(byte[] content, String source, java.util.Map<String, String> metadata) {
			metadata.put(DocumentLoader.META_FORMAT, "markup");
			return List.of(Document.of(source, new String(content, StandardCharsets.UTF_8), metadata));
		}
	}
}
