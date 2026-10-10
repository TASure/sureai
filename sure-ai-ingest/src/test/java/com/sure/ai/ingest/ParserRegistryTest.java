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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * {@link ParserRegistry} 白盒测试：扩展名归一化、Content-Type 兜底推断与路由异常分支。
 *
 * <p>覆盖点：{@code extensionOf} 的 null / 无后缀 / 点结尾 / 大写分支，
 * {@code extensionByContentType} 的 null 与 html/pdf/markdown/text-plain/未知分支，
 * 以及自定义解析器（扩展名不带前导点）的注册归一化与路由。</p>
 */
public class ParserRegistryTest {

	/**
	 * extensionOf：null、无点、点结尾、大写后缀。
	 */
	@Test
	public void testExtensionOf() {
		assertNull(ParserRegistry.extensionOf(null));
		assertNull(ParserRegistry.extensionOf("noext"));
		assertNull(ParserRegistry.extensionOf("trailing."));
		assertEquals(".txt", ParserRegistry.extensionOf("a.TXT"));
		assertEquals(".pdf", ParserRegistry.extensionOf("dir/final.PDF"));
	}

	/**
	 * extensionByContentType：null 与各类分支。
	 */
	@Test
	public void testExtensionByContentType() {
		assertNull(ParserRegistry.extensionByContentType(null));
		assertEquals(".html", ParserRegistry.extensionByContentType("text/html; charset=utf-8"));
		assertEquals(".html", ParserRegistry.extensionByContentType("application/xhtml+xml"));
		assertEquals(".pdf", ParserRegistry.extensionByContentType("application/pdf"));
		assertEquals(".md", ParserRegistry.extensionByContentType("text/markdown"));
		assertEquals(".txt", ParserRegistry.extensionByContentType("text/plain"));
		assertNull(ParserRegistry.extensionByContentType("application/octet-stream"));
		assertNull(ParserRegistry.extensionByContentType("image/png"));
	}

	/**
	 * 自定义解析器扩展名不带前导点，注册时归一化补点后仍可路由。
	 */
	@Test
	public void testRegisterExtensionWithoutDot() {
		ParserRegistry registry = new ParserRegistry();
		registry.register(new FixedParser(".custom".substring(1), "custom-body"));
		DocumentParser parser = registry.parserFor("data.custom");
		assertEquals("custom-body", parser.parse(new byte[0], "data.custom", new HashMap<>()).get(0).text());
	}

	/**
	 * 路由：不支持的格式（filename 为 null 与空串）抛 IllegalStateException。
	 */
	@Test
	public void testRouteUnsupported() {
		ParserRegistry registry = ParserRegistry.withDefaults();
		try {
			registry.route(new byte[0], null, "x", null);
			throw new AssertionError("应抛出 IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("不支持"));
		}
		try {
			registry.route(new byte[0], "", "y", null);
			throw new AssertionError("应抛出 IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("不支持"));
		}
	}

	/**
	 * 路由：带 content-type 时元数据写入，且 source/loaded_at 齐全。
	 */
	@Test
	public void testRouteMetadata() {
		ParserRegistry registry = ParserRegistry.withDefaults();
		List<Document> docs = registry.route("hello".getBytes(), "a.txt", "file:///a.txt", "text/plain");
		assertEquals(1, docs.size());
		Map<String, String> meta = docs.get(0).metadata();
		assertEquals("file:///a.txt", meta.get(DocumentLoader.META_SOURCE));
		assertEquals("text/plain", meta.get(DocumentLoader.META_CONTENT_TYPE));
		assertTrue(meta.get(DocumentLoader.META_LOADED_AT).length() > 0);
	}

	/**
	 * parserFor：无后缀返回 null。
	 */
	@Test
	public void testParserForUnknown() {
		ParserRegistry registry = ParserRegistry.withDefaults();
		assertNull(registry.parserFor("readme"));
	}

	/**
	 * 固定输出的测试解析器。
	 */
	private static final class FixedParser implements DocumentParser {

		/** 支持的扩展名。 */
		private final String ext;

		/** 解析输出文本。 */
		private final String body;

		FixedParser(String ext, String body) {
			this.ext = ext;
			this.body = body;
		}

		@Override
		public Set<String> extensions() {
			return Set.of(this.ext);
		}

		@Override
		public List<Document> parse(byte[] content, String source, Map<String, String> metadata) {
			metadata.put(DocumentLoader.META_FORMAT, "custom");
			return List.of(Document.of(source, this.body, metadata));
		}
	}
}
