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

package com.sure.ai.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link JsonMapper} record 反序列化 / 序列化测试。
 *
 * @author sureai
 * @since 0.2.0
 */
public class JsonMapperTest {

	/** 嵌套记录：作者。 */
	public record Author(String name, int age) {
	}

	/** 嵌套记录：文章，含标量 / 嵌套 record / List。 */
	public record Article(String title, long wordCount, double score, boolean published,
			Author author, List<String> tags) {
	}

	/** 从 JsonObject 反序列化 record：标量、嵌套 record、List 字段。 */
	@Test
	public void testFromJsonRecord() {
		JsonObject author = Json.object();
		author.put("name", "张三");
		author.put("age", 30);
		JsonObject obj = Json.object();
		obj.put("title", "sureai 指南");
		obj.put("wordCount", 1200L);
		obj.put("score", 4.5d);
		obj.put("published", true);
		obj.set("author", author);
		JsonArray tags = Json.array();
		tags.add("java");
		tags.add("ai");
		obj.set("tags", tags);

		Article article = JsonMapper.fromJson(obj, Article.class);
		assertEquals("sureai 指南", article.title());
		assertEquals(1200L, article.wordCount());
		assertEquals(4.5d, article.score(), 1e-9);
		assertTrue(article.published());
		assertEquals("张三", article.author().name());
		assertEquals(30, article.author().age());
		assertEquals(2, article.tags().size());
		assertEquals("java", article.tags().get(0));
	}

	/** 缺失字段：引用类型为 null，原始类型取默认值。 */
	@Test
	public void testFromJsonMissingFields() {
		JsonObject obj = Json.object();
		obj.put("name", "李四");
		Author author = JsonMapper.fromJson(obj, Author.class);
		assertEquals("李四", author.name());
		assertEquals(0, author.age());
	}

	/** record → JsonObject 序列化并往返。 */
	@Test
	public void testToJsonRoundTrip() {
		Article article = new Article("往返", 10L, 1.0d, false,
			new Author("王五", 25), List.of("x"));
		JsonObject jo = JsonMapper.toJsonObject(article);
		assertEquals("往返", jo.getString("title"));
		assertEquals(10L, jo.optLong("wordCount", 0L));
		JsonObject author = jo.getJsonObject("author");
		assertEquals("王五", author.getString("name"));
		JsonArray tags = jo.getJsonArray("tags");
		assertEquals("x", tags.getString(0));

		// 往返一致
		Article back = JsonMapper.fromJson(jo, Article.class);
		assertEquals(article.title(), back.title());
		assertEquals(article.author().name(), back.author().name());
		assertEquals(article.tags(), back.tags());
	}

	/** null 入参返回 null。 */
	@Test
	public void testNull() {
		assertNull(JsonMapper.fromJson(null, Article.class));
		assertNull(JsonMapper.toJsonObject(null));
	}

	/** 非 record 类型反序列化抛异常。 */
	@Test(expected = com.sure.ai.exception.AiException.class)
	public void testNonRecordRejected() {
		JsonMapper.fromJson(Json.object(), String.class);
	}
}
