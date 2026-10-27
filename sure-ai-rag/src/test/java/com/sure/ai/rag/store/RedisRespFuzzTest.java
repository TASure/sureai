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

package com.sure.ai.rag.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.store.resp.RespCodec;

/**
 * Redis RESP2 编解码模糊健壮性测试（sureai v2.1.0「生产级信任」批次 1；v2.4.0 迁移至
 * 共享 {@link RespCodec}，v1.8.0 内联于 {@code RedisVectorStore} 的私有方法已抽取）。
 *
 * <p>零真实网络：不建 Socket。用 {@link ByteArrayInputStream} 喂字节流给
 * {@link RespCodec#readReply(InputStream)}，{@link RespCodec#encode(List)} 编码后再喂回。
 * 方法：固定种子 {@code Random(42)} + 边界枚举（非法长度、截断、负长度、畸形批量串、
 * 超大声明长度）。断言原则：只允许正常返回或 {@link AiException}/{@link java.io.IOException}；
 * 严禁 {@link NumberFormatException}/{@link OutOfMemoryError}/{@link NegativeArraySizeException}/
 * {@link NullPointerException} 逃逸。</p>
 *
 * @author sureai
 * @since 2.1.0
 */
public class RedisRespFuzzTest {

	/** 固定随机种子。 */
	private static final long SEED = 42L;

	/**
	 * 编解码往返：合法 RESP 请求编码后再喂回解析器，结构必须正确还原。
	 *
	 * @throws Exception IO 异常
	 */
	@Test
	public void encodeDecodeRoundTrip() throws Exception {
		byte[] wire = RespCodec.encode(List.of("HSET", "doc:1", "text", "hello"));
		assertNotNull(wire);
		// 服务端回一个批量串回复
		Object resp = readReply("$5\r\nhello\r\n");
		assertEquals("hello", resp);
		// 数组回复
		Object arr = readReply("*2\r\n:3\r\n$5\r\nworld\r\n");
		assertNotNull(arr);
		assertTrue(arr instanceof List);
		assertEquals(2, ((List<?>) arr).size());
		// 空串批量
		assertEquals("", readReply("$0\r\n\r\n"));
		// 负长度 -> null
		assertNull(readReply("$-1\r\n"));
		assertNull(readReply("*-1\r\n"));
	}

	/**
	 * 非法长度/数字：长度字段非数字时，必须转成 {@link AiException}，不得抛 {@link NumberFormatException}。
	 */
	@Test
	public void malformedLengths() {
		assertGraceful(":abc\r\n");          // 整数回复非数字
		assertGraceful("*abc\r\n");          // 数组元素数非数字
		assertGraceful("$xyz\r\n");          // 批量串长度非数字
		assertGraceful("*\r\n");
		assertGraceful("$\r\n");
		assertGraceful(":+3\r\n");
		assertGraceful("*  5\r\n");
	}

	/**
	 * 超大声明长度：恶意服务器宣称天文数字长度时，必须优雅拒绝（AiException），
	 * 不得 {@link OutOfMemoryError}/{@link NegativeArraySizeException}。
	 */
	@Test
	public void hugeDeclaredLengths() {
		assertGraceful("*99999999999999999999\r\n");          // 天文数组元素数
		assertGraceful("$99999999999999999999\r\n");          // 天文 bulk 长度
		assertGraceful("*5000000000\r\n");                    // 超 Integer.MAX 截断后为负
		assertGraceful("$3000000000\r\n");                    // 超 int 的 bulk
	}

	/**
	 * 截断/畸形字节流：流中途断开、CRLF 缺失、尾字节错乱，都必须 AiException 优雅收尾。
	 */
	@Test
	public void truncatedStreams() {
		assertGraceful("*2\r\n$3\r\nfoo\r");          // bulk 尾 CRLF 缺 LF
		assertGraceful("$5\r\nab\r\n");                // 声明 5 字节只给 2
		assertGraceful("*1\r\n");                      // 声明 1 元素后流结束
		assertGraceful("$5\r\nhelloXX");               // bulk 后字节非 CRLF
		assertGraceful("+");                           // 简单字符串无换行
		assertGraceful("-ERR oops");                   // 错误回复无 CRLF
		assertGraceful("#");                           // 未知类型字节
		assertGraceful("");                            // 空流
	}

	/**
	 * 随机字节 fuzz：固定种子 5000 段随机字节喂给 readReply，不得 JVM 崩溃。
	 */
	@Test
	public void fuzzRandomBytes() {
		Random rnd = new Random(SEED);
		for (int round = 0; round < 5000; round++) {
			int len = rnd.nextInt(64);
			byte[] data = new byte[len];
			rnd.nextBytes(data);
			assertGraceful(data);
		}
	}

	/** 以字节数组喂入 readReply。 */
	private static Object readReply(String wire) throws Exception {
		return readReply(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** 以字节数组喂入 readReply。 */
	private static Object readReply(byte[] wire) throws Exception {
		try (InputStream in = new ByteArrayInputStream(wire)) {
			return RespCodec.readReply(in);
		}
	}

	/** 断言：要么正常返回，要么 AiException / IOException；严禁 JVM 级崩溃。 */
	private static void assertGraceful(String wire) {
		assertGraceful(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** 断言：要么正常返回，要么 AiException / IOException；严禁 JVM 级崩溃。 */
	private static void assertGraceful(byte[] wire) {
		try {
			readReply(wire);
		} catch (AiException | java.io.IOException e) {
			return; // 业务协议错误 / 真实 IO：按既有契约允许
		} catch (Throwable t) {
			if (t instanceof StackOverflowError || t instanceof OutOfMemoryError
					|| t instanceof NullPointerException || t instanceof NumberFormatException
					|| t instanceof NegativeArraySizeException
					|| t instanceof ArrayIndexOutOfBoundsException) {
				fail("RESP 解码触发 JVM 级崩溃 " + t.getClass().getName()
					+ "，cause=" + t.getMessage());
			}
			// 其它 RuntimeException：也视为不应逃逸
			fail("RESP 解码抛出非预期异常 " + t);
		}
	}
}
