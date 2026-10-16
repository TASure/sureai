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
package com.sure.ai.rag.store.mongodb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link Bson} 编解码器单元测试：Doc 全类型 roundtrip + 手工构造的服务端扩展类型
 * （ObjectId/binary/undefined/regex/timestamp/datetime/int64）解码与异常路径，零网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class BsonTest {

	/** Doc 编码基础类型后 parse 还原。 */
	@Test
	public void testRoundtripBasic() {
		Bson.Doc doc = new Bson.Doc()
				.addDouble("d", 3.14)
				.addString("s", "hello")
				.addBoolean("b", true)
				.addNull("n")
				.addInt32("i", 42);
		Map<String, Object> m = Bson.parse(doc.encode());
		assertEquals(3.14, (Double) m.get("d"), 1e-9);
		assertEquals("hello", m.get("s"));
		assertEquals(Boolean.TRUE, m.get("b"));
		assertNull(m.get("n"));
		assertEquals(42, m.get("i"));
	}

	/** 嵌套文档 + 数组 roundtrip。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testNestedAndArray() {
		Bson.Doc inner = new Bson.Doc().addString("x", "1");
		Bson.Doc arr = new Bson.Doc().addDouble("0", 1.0).addDouble("1", 2.0);
		Bson.Doc doc = new Bson.Doc().addDoc("sub", inner).addArray("vec", arr);
		Map<String, Object> m = Bson.parse(doc.encode());
		Map<String, Object> sub = (Map<String, Object>) m.get("sub");
		assertEquals("1", sub.get("x"));
		List<Object> vec = (List<Object>) m.get("vec");
		assertEquals(2, vec.size());
		assertEquals(2.0, (Double) vec.get(1), 1e-9);
	}

	/** addString(null) 按空串编码，不 NPE。 */
	@Test
	public void testNullString() {
		Map<String, Object> m = Bson.parse(new Bson.Doc().addString("s", null).encode());
		assertEquals("", m.get("s"));
	}

	/** 手工构造含服务端扩展类型的 BSON：double/string/doc/array/binary/boolean/null/int32/int64/oid/undefined/regex/timestamp/datetime。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testServerExtensionTypes() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		// 0x01 double
		elem(body, 0x01, "d", bb -> bb.putDouble(2.5));
		// 0x05 binary：len=3 + subtype=0 + 3 bytes
		elem(body, 0x05, "bin", bb -> {
			bb.putInt(3);
			bb.put((byte) 0);
			bb.put(new byte[] { 1, 2, 3 });
		});
		// 0x07 ObjectId：12 bytes
		elem(body, 0x07, "oid", bb -> bb.put(new byte[12]));
		// 0x08 boolean true
		elem(body, 0x08, "bool", bb -> bb.put((byte) 1));
		// 0x0A null
		elem(body, 0x0A, "nul", bb -> {
		});
		// 0x0B regex：两个 cstring
		elem(body, 0x0B, "re", bb -> {
			bb.put("abc".getBytes(StandardCharsets.UTF_8));
			bb.put((byte) 0);
			bb.put("i".getBytes(StandardCharsets.UTF_8));
			bb.put((byte) 0);
		});
		// 0x06 undefined
		elem(body, 0x06, "undef", bb -> {
		});
		// 0x09 datetime：int64
		elem(body, 0x09, "dt", bb -> bb.putLong(1_700_000_000_000L));
		// 0x10 int32
		elem(body, 0x10, "i32", bb -> bb.putInt(7));
		// 0x11 timestamp：int64
		elem(body, 0x11, "ts", bb -> bb.putLong(99));
		// 0x12 int64
		elem(body, 0x12, "i64", bb -> bb.putLong(123456789L));

		int total = 4 + body.size() + 1;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeInt32LE(out, total);
		out.write(body.toByteArray(), 0, body.size());
		out.write(0);

		Map<String, Object> m = Bson.parse(out.toByteArray());
		assertEquals(2.5, (Double) m.get("d"), 1e-9);
		assertArrayEquals(new byte[] { 1, 2, 3 }, (byte[]) m.get("bin"));
		assertTrue(((byte[]) m.get("oid")).length == 12);
		assertEquals(Boolean.TRUE, m.get("bool"));
		assertNull(m.get("nul"));
		assertNull(m.get("re"));
		assertNull(m.get("undef"));
		assertEquals(1_700_000_000_000L, m.get("dt"));
		assertEquals(7, m.get("i32"));
		assertNull(m.get("ts")); // 0x11 timestamp 消费 8 字节后保守返回 null
		assertEquals(123456789L, m.get("i64"));
	}

	/** 未知元素类型 0x20 抛 AiException。 */
	@Test
	public void testUnknownTypeThrows() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		// 0x20 未知
		body.write(0x20);
		body.writeBytes("x".getBytes(StandardCharsets.UTF_8));
		body.write(0);
		int total = 4 + body.size() + 1;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeInt32LE(out, total);
		out.write(body.toByteArray(), 0, body.size());
		out.write(0);
		assertThrows(AiException.class, () -> Bson.parse(out.toByteArray()));
	}

	// ===== 手工 BSON 元素写入辅助 =====

	private interface Writer {
		void write(ByteBuffer bb);
	}

	private static void elem(ByteArrayOutputStream body, int type, String name, Writer w) {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		ByteBuffer bb = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		w.write(bb);
		bb.flip();
		byte[] val = new byte[bb.remaining()];
		bb.get(val);
		body.write(type);
		body.writeBytes(name.getBytes(StandardCharsets.UTF_8));
		body.write(0);
		body.write(val, 0, val.length);
	}

	private static void writeInt32LE(ByteArrayOutputStream out, int v) {
		out.write(v & 0xFF);
		out.write((v >> 8) & 0xFF);
		out.write((v >> 16) & 0xFF);
		out.write((v >> 24) & 0xFF);
	}
}
