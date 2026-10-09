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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.exception.AiException;

/**
 * 最小 BSON 编解码器（JDK 原生，零第三方依赖），供 {@code MongoDbVectorStore} 编码 OP_MSG 命令、
 * 解码服务端响应。
 *
 * <p>BSON 为<b>小端</b>二进制格式，文档 = {@code int32 总字节数 + 元素序列 + 0x00 终止符}；
 * 元素 = {@code 1 字节类型 + cstring 字段名 + 值}。本实现仅覆盖向量库所需的有限子集：</p>
 * <ul>
 *   <li>0x01 double（8 字节 LE）、0x02 string（int32+utf8+NUL）、0x03 embedded document、
 *       0x04 array（键为 "0","1",... 的文档）、0x05 binary、0x08 boolean、0x0A null、
 *       0x10 int32、0x12 int64；</li>
 *   <li>解码额外容忍 0x07 ObjectId(12B)、0x09 datetime(8B)、0x11 timestamp(8B)、0x06 undefined
 *       等服务端可能附加的字段，跳过不报错。</li>
 * </ul>
 *
 * <p>规范来源：<a href="https://bsonspec.org/spec">BSON Specification v1.1</a>。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class Bson {

	private Bson() {
		throw new AssertionError("No instances");
	}

	// ===== 编码 =====

	/**
	 * BSON 文档构建器：累加「类型字节 + cstring 名 + 值」，最终计算总长度前缀。
	 */
	public static final class Doc {
		private final ByteArrayOutputStream body = new ByteArrayOutputStream();

		/**
		 * 追加 double 字段。
		 *
		 * @param name 字段名
		 * @param v    值
		 * @return this
		 */
		public Doc addDouble(String name, double v) {
			entry(0x01, name);
			byte[] b = new byte[8];
			ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putDouble(v);
			body.write(b, 0, 8);
			return this;
		}

		/**
		 * 追加 string 字段。
		 *
		 * @param name 字段名
		 * @param v    值
		 * @return this
		 */
		public Doc addString(String name, String v) {
			entry(0x02, name);
			byte[] utf8 = (v == null ? "" : v).getBytes(StandardCharsets.UTF_8);
			writeInt32(body, utf8.length + 1);
			body.writeBytes(utf8);
			body.write(0);
			return this;
		}

		/**
		 * 追加 int32 字段。
		 *
		 * @param name 字段名
		 * @param v    值
		 * @return this
		 */
		public Doc addInt32(String name, int v) {
			entry(0x10, name);
			writeInt32(body, v);
			return this;
		}

		/**
		 * 追加 boolean 字段。
		 *
		 * @param name 字段名
		 * @param v    值
		 * @return this
		 */
		public Doc addBoolean(String name, boolean v) {
			entry(0x08, name);
			body.write(v ? 1 : 0);
			return this;
		}

		/**
		 * 追加 null 字段。
		 *
		 * @param name 字段名
		 * @return this
		 */
		public Doc addNull(String name) {
			entry(0x0A, name);
			return this;
		}

		/**
		 * 追加嵌套文档字段。
		 *
		 * @param name 字段名
		 * @param doc  子文档
		 * @return this
		 */
		public Doc addDoc(String name, Doc doc) {
			entry(0x03, name);
			byte[] enc = doc.encode();
			body.write(enc, 0, enc.length);
			return this;
		}

		/**
		 * 追加数组字段（数组用键为 "0","1",... 的文档表达；传入的 arrayDoc 应按序添加 "0","1"...）。
		 *
		 * @param name     字段名
		 * @param arrayDoc 数组文档
		 * @return this
		 */
		public Doc addArray(String name, Doc arrayDoc) {
			entry(0x04, name);
			byte[] enc = arrayDoc.encode();
			body.write(enc, 0, enc.length);
			return this;
		}

		private void entry(int type, String name) {
			body.write(type);
			body.writeBytes(name.getBytes(StandardCharsets.UTF_8));
			body.write(0);
		}

		/**
		 * 序列化为完整 BSON 文档字节（含 int32 长度前缀与 0x00 终止符）。
		 *
		 * @return BSON 字节
		 */
		public byte[] encode() {
			int total = 4 + body.size() + 1;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeInt32(out, total);
			out.write(body.toByteArray(), 0, body.size());
			out.write(0);
			return out.toByteArray();
		}
	}

	// ===== 解码 =====

	/**
	 * 解析 BSON 文档为有序 Map（嵌套文档为 Map，数组为 List）。
	 *
	 * @param data BSON 字节
	 * @return 有序字段映射
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> parse(byte[] data) {
		ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		return parseDocument(bb);
	}

	private static Map<String, Object> parseDocument(ByteBuffer bb) {
		int total = bb.getInt();
		Map<String, Object> map = new LinkedHashMap<>();
		int end = bb.position() + total - 4;
		while (bb.position() < end - 1) {
			int type = bb.get();
			String name = readCString(bb);
			Object value = switch (type) {
				case 0x01 -> bb.getDouble();
				case 0x02 -> readBsonString(bb);
				case 0x03 -> parseDocument(bb);
				case 0x04 -> {
					Map<String, Object> arr = parseDocument(bb);
					List<Object> list = new ArrayList<>(arr.size());
					for (int i = 0; arr.containsKey(String.valueOf(i)); i++) {
						list.add(arr.get(String.valueOf(i)));
					}
					yield list;
				}
				case 0x05 -> {
					int len = bb.getInt();
					bb.get(); // subtype
					byte[] d = new byte[len];
					bb.get(d);
					yield d;
				}
				case 0x08 -> bb.get() != 0;
				case 0x0A -> null;
				case 0x10 -> bb.getInt();
				case 0x12 -> bb.getLong();
				case 0x07 -> {
					byte[] oid = new byte[12];
					bb.get(oid);
					yield oid;
				}
				case 0x06, 0x0B, 0x11 -> {
					// undefined / regex(cstring,cstring) / timestamp(8B)：保守跳过。
					if (type == 0x11) {
						bb.getLong();
					} else if (type == 0x0B) {
						readCString(bb);
						readCString(bb);
					}
					yield null;
				}
				case 0x09 -> bb.getLong(); // datetime
				default -> throw new AiException("BSON 不支持的元素类型: 0x" + Integer.toHexString(type));
			};
			map.put(name, value);
		}
		bb.get(); // 消费文档结尾 0x00 终止符，使父文档解析器越过本文档
		return map;
	}

	private static String readBsonString(ByteBuffer bb) {
		int len = bb.getInt(); // 含结尾 NUL
		byte[] buf = new byte[len - 1];
		bb.get(buf);
		bb.get(); // NUL
		return new String(buf, StandardCharsets.UTF_8);
	}

	private static String readCString(ByteBuffer bb) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte b;
		while ((b = bb.get()) != 0) {
			out.write(b);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void writeInt32(ByteArrayOutputStream out, int v) {
		out.write(v & 0xFF);
		out.write((v >> 8) & 0xFF);
		out.write((v >> 16) & 0xFF);
		out.write((v >> 24) & 0xFF);
	}
}
