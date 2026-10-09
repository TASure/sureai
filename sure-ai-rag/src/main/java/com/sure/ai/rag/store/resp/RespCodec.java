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

package com.sure.ai.rag.store.resp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sure.ai.exception.AiException;

/**
 * 最小 RESP2（REdis Serialization Protocol）编解码，JDK 原生实现，零第三方依赖。
 *
 * <p>v1.8.0 内联于 {@code RedisVectorStore}；v2.4.0 抽取为共享客户端，供
 * {@code RedisVectorStore}（向量库）与 {@code RedisCacheStore}（对话缓存）复用，
 * 避免两份 RESP 解析逻辑漂移。</p>
 *
 * <p>编码：{@code *n\r\n$len\r\narg\r\n}（数组 + 批量串），{@code byte[]} 原样写入二进制 bulk。
 * 解码：支持简单字符串 {@code +}、错误 {@code -}（包装为 {@link AiException}）、整数 {@code :}、
 * 批量串 {@code $}（{@code -1} 长度返回 {@code null}）、数组 {@code *}、null {@code _}。</p>
 *
 * <p>健壮性：数组元素数与 bulk 长度设上限，恶意服务器宣称天文数量时抛 {@link AiException}
 * 而非 {@link OutOfMemoryError}/{@link NegativeArraySizeException}；数字字段解析失败一律转
 * {@link AiException}，不令 {@link NumberFormatException} 逃逸。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class RespCodec {

	/** 数组元素数上限：防御恶意服务器宣称天文数量导致 {@link OutOfMemoryError}。 */
	public static final long MAX_ARRAY_ELEMENTS = 10_000_000L;

	private RespCodec() {
		throw new AssertionError("No instances");
	}

	/**
	 * 把命令参数列表编码为 RESP 协议字节（数组 + 批量串）。
	 *
	 * @param args 命令参数；{@code byte[]} 按二进制 bulk 写入，其余按 UTF-8 字符串
	 * @return 可直接写入 Socket 输出流的字节
	 */
	public static byte[] encode(List<Object> args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeRaw(out, "*" + args.size() + "\r\n");
		for (Object arg : args) {
			if (arg instanceof byte[] bytes) {
				writeRaw(out, "$" + bytes.length + "\r\n");
				out.write(bytes, 0, bytes.length);
				writeRaw(out, "\r\n");
			} else {
				byte[] bytes = String.valueOf(arg).getBytes(StandardCharsets.UTF_8);
				writeRaw(out, "$" + bytes.length + "\r\n");
				out.write(bytes, 0, bytes.length);
				writeRaw(out, "\r\n");
			}
		}
		return out.toByteArray();
	}

	/**
	 * 从输入流读取一条 RESP 回复。
	 *
	 * @param in Socket 输入流
	 * @return 简单字符串(String) / 整数(Long) / 批量串(String 或 null) / 数组(List 或 null) / null
	 * @throws IOException     底层 IO 异常
	 * @throws AiException     协议错误、错误回复、畸形数据
	 */
	public static Object readReply(InputStream in) throws IOException {
		int b = in.read();
		if (b == -1) {
			throw new AiException("Redis 连接已关闭");
		}
		return switch (b) {
			case '*' -> readArray(in);
			case '$' -> readBulk(in);
			case ':' -> parseCount(readLine(in));
			case '+' -> readLine(in);
			case '-' -> throw new AiException("Redis 错误: " + readLine(in));
			case '_' -> {
				readLine(in);
				yield null;
			}
			default -> throw new AiException("未知 RESP 类型: " + (char) b);
		};
	}

	/** 解析 RESP 数组回复。 */
	private static Object readArray(InputStream in) throws IOException {
		long count = parseCount(readLine(in));
		if (count < 0) {
			return null;
		}
		if (count > MAX_ARRAY_ELEMENTS) {
			throw new AiException("Redis 数组元素数超过上限: " + count);
		}
		List<Object> list = new ArrayList<>((int) count);
		for (long i = 0; i < count; i++) {
			list.add(readReply(in));
		}
		return list;
	}

	/** 解析 RESP 批量串回复（返回 UTF-8 字符串，{@code -1} 长度为 null）。 */
	private static Object readBulk(InputStream in) throws IOException {
		long len = parseCount(readLine(in));
		if (len < 0) {
			return null;
		}
		if (len > Integer.MAX_VALUE) {
			throw new AiException("Redis bulk 长度超过可分配上限: " + len);
		}
		byte[] buf = new byte[(int) len];
		int read = 0;
		while (read < len) {
			int r = in.read(buf, read, (int) len - read);
			if (r == -1) {
				throw new AiException("Redis bulk 读取不完整");
			}
			read += r;
		}
		int cr = in.read();
		int lf = in.read();
		if (cr != '\r' || lf != '\n') {
			throw new AiException("Redis bulk 结尾 CRLF 缺失");
		}
		return new String(buf, StandardCharsets.UTF_8);
	}

	/**
	 * 解析 RESP 行内十进制整数长度/计数：非数字或超出 long 范围一律转业务异常，
	 * 不得让 {@link NumberFormatException} 逃逸。
	 */
	public static long parseCount(String line) {
		try {
			return Long.parseLong(line);
		} catch (NumberFormatException e) {
			throw new AiException("Redis 数字字段非法: '" + line + "'");
		}
	}

	/** 读取一行（以 CRLF 结尾），去掉结尾 CR。 */
	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int prev = -1;
		int c;
		while ((c = in.read()) != -1) {
			if (prev == '\r' && c == '\n') {
				byte[] bytes = out.toByteArray();
				return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
			}
			out.write(c);
			prev = c;
		}
		throw new AiException("Redis 行读取不完整");
	}

	/** 以 UTF-8 追加原始字符串。 */
	private static void writeRaw(ByteArrayOutputStream out, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.write(bytes, 0, bytes.length);
	}
}
