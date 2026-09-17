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

package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import com.sure.ai.rag.loader.TxtDocumentLoader;
import com.sure.ai.rag.model.Document;

/**
 * {@link TxtDocumentLoader} 测试。
 */
public class TxtDocumentLoaderTest {

	/** 临时目录规则。 */
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void testLoadFromTempFile() throws Exception {
		Path file = temp.newFile("note.txt").toPath();
		Files.writeString(file, "hello sureai", StandardCharsets.UTF_8);
		TxtDocumentLoader loader = new TxtDocumentLoader(file);
		List<Document> documents = loader.load();
		assertEquals(1, documents.size());
		Document doc = documents.get(0);
		assertEquals("hello sureai", doc.text());
		assertEquals(file.toString(), doc.id());
		assertEquals(file.toString(), doc.metadata().get(TxtDocumentLoader.META_SOURCE));
		assertNotNull(doc.metadata().get(TxtDocumentLoader.META_LOADED_AT));
	}

	@Test
	public void testLoadFromInputStream() {
		String content = "stream body";
		ByteArrayInputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		TxtDocumentLoader loader = new TxtDocumentLoader(in, "my-stream");
		List<Document> documents = loader.load();
		assertEquals(1, documents.size());
		assertEquals("stream body", documents.get(0).text());
		assertEquals("my-stream", documents.get(0).id());
		assertEquals("my-stream", documents.get(0).metadata().get(TxtDocumentLoader.META_SOURCE));
	}

	@Test
	public void testLoadEmptyFile() throws Exception {
		Path file = temp.newFile("empty.txt").toPath();
		Files.writeString(file, "", StandardCharsets.UTF_8);
		TxtDocumentLoader loader = new TxtDocumentLoader(file);
		assertTrue(loader.load().isEmpty());
	}

	@Test(expected = IllegalStateException.class)
	public void testLoadNonexistentFile() {
		new TxtDocumentLoader(Path.of("/nonexistent/sureai/nope.txt")).load();
	}

	@Test
	public void testCharset() throws Exception {
		Path file = temp.newFile("zh.txt").toPath();
		Files.write(file, "中文内容测试".getBytes(StandardCharsets.UTF_8));
		TxtDocumentLoader loader = new TxtDocumentLoader(file, StandardCharsets.UTF_8);
		List<Document> documents = loader.load();
		assertEquals(1, documents.size());
		assertEquals("中文内容测试", documents.get(0).text());
	}

	@Test
	public void testFromStringSource() {
		TxtDocumentLoader loader = new TxtDocumentLoader("inline text", "inline");
		List<Document> documents = loader.load();
		assertEquals(1, documents.size());
		assertEquals("inline text", documents.get(0).text());
		assertEquals("inline", loader.sourceName());
	}
}
