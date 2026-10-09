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
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.sure.ai.rag.model.Document;

/**
 * {@link FileSystemLoader} 测试：扩展名路由、单文件、空文件、异常分支。
 */
public class FileSystemLoaderTest {

	/** 临时目录。 */
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final FileSystemLoader loader = FileSystemLoader.createDefault();

	@Test
	public void testLoadTxt() throws Exception {
		Path file = temp.newFile("note.txt").toPath();
		Files.writeString(file, "hello file", StandardCharsets.UTF_8);
		List<Document> docs = loader.load(file);
		assertEquals(1, docs.size());
		assertEquals("hello file", docs.get(0).text());
		assertEquals("txt", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
		assertEquals(file.toString(), docs.get(0).metadata().get(DocumentLoader.META_SOURCE));
		assertNotNull(docs.get(0).metadata().get(DocumentLoader.META_LOADED_AT));
	}

	@Test
	public void testLoadMarkdownKeepsRawText() throws Exception {
		Path file = temp.newFile("doc.md").toPath();
		Files.writeString(file, "# Title\n- item", StandardCharsets.UTF_8);
		List<Document> docs = loader.load(file);
		assertEquals(1, docs.size());
		// 纯文本直读：保留 Markdown 标记
		assertTrue(docs.get(0).text().contains("# Title"));
		assertEquals("md", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
	}

	@Test
	public void testLoadHtmlRoutes() throws Exception {
		Path file = temp.newFile("page.html").toPath();
		Files.writeString(file, "<html><body><p>body text</p></body></html>", StandardCharsets.UTF_8);
		List<Document> docs = loader.load(file);
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("body text"));
		assertEquals("html", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
	}

	@Test
	public void testEmptyFileReturnsEmpty() throws Exception {
		Path file = temp.newFile("empty.txt").toPath();
		Files.writeString(file, "", StandardCharsets.UTF_8);
		assertTrue(loader.load(file).isEmpty());
	}

	@Test(expected = IllegalStateException.class)
	public void testFileNotFound() {
		loader.load(Path.of("/nonexistent/sureai/missing.txt"));
	}

	@Test(expected = IllegalStateException.class)
	public void testUnsupportedExtension() throws Exception {
		Path file = temp.newFile("data.xyz").toPath();
		Files.writeString(file, "binary", StandardCharsets.UTF_8);
		loader.load(file);
	}

	@Test
	public void testLoadFileUri() throws Exception {
		Path file = temp.newFile("uri.txt").toPath();
		Files.writeString(file, "via uri", StandardCharsets.UTF_8);
		List<Document> docs = loader.load(file.toUri());
		assertEquals(1, docs.size());
		assertEquals("via uri", docs.get(0).text());
	}

	@Test(expected = IllegalStateException.class)
	public void testLoadHttpUriRejected() {
		loader.load(java.net.URI.create("http://example.com/x.txt"));
	}
}
