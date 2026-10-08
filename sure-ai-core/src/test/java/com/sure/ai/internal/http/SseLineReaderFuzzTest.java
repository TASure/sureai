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

package com.sure.ai.internal.http;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link SseLineReader} 模糊健壮性测试（sureai v2.1.0「生产级信任」批次 1）。
 *
 * <p>零真实网络、零新依赖。方法：固定种子 {@code Random(42)} 生成随机字节流，
 * 叠加边界枚举（超长行、截断帧、非法 UTF-8、控制字符、{@code \r\n} 边界、无尾换行）。
 * 断言原则：只允许正常返回或业务异常 {@link AiException}（IO 错误包装）；
 * 严禁 {@link NullPointerException}/{@link IndexOutOfBoundsException}/JVM 级 Error 逃逸。</p>
 *
 * @author sureai
 * @since 2.1.0
 */
public class SseLineReaderFuzzTest {

	/** 固定随机种子。 */
	private static final long SEED = 42L;

	/**
	 * 随机字节流 fuzz：固定种子生成 500 段随机字节，以 UTF-8/ISO-8859-1 喂入，只允许 AiException。
	 */
	@Test
	public void fuzzRandomBytes() {
		Random rnd = new Random(SEED);
		for (int round = 0; round < 500; round++) {
			int len = 1 + rnd.nextInt(256);
			byte[] data = new byte[len];
			rnd.nextBytes(data);
			assertNoJvmCrash(data, round % 2 == 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
		}
	}

	/**
	 * 边界行：超长 data 行、纯冒号注释、无冒号字段、{@code \r\n} 与裸 {@code \n} 混用、无尾换行。
	 */
	@Test
	public void fuzzBoundaryLines() {
		// 1MB 单行 data
		StringBuilder longLine = new StringBuilder(1024 * 1024 + 16);
		longLine.append("data: ");
		for (int i = 0; i < 1024 * 1024; i++) {
			longLine.append('x');
		}
		longLine.append('\n').append('\n');
		assertNoJvmCrash(longLine.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

		// \r\n 边界 + 裸 \n 混用
		assertNoJvmCrash("data: a\r\nevent: msg\r\n\r\n".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		// 无尾换行、无空行收尾
		assertNoJvmCrash("data: trailing".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		// 纯注释行与空字段
		assertNoJvmCrash(": comment\n:another\n\n".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		// 无冒号的字段行
		assertNoJvmCrash("garbagefield\nid: 1\nretry: 5000\n\n".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		// 截断：字段值缺尾
		assertNoJvmCrash("data: partial\nevent:".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	/**
	 * 非法 UTF-8 字节序列：解码器按替换字符容错，不得抛异常。
	 */
	@Test
	public void fuzzIllegalUtf8() {
		byte[] bad = new byte[] { (byte) 0xFF, (byte) 0xFE, (byte) 0xC0, (byte) 0x80,
			(byte) 0xED, (byte) 0xA0, (byte) 0x80, '\n', 'd', 'a', 't', 'a', ':', ' ', 1, 2, 3 };
		assertNoJvmCrash(bad, StandardCharsets.UTF_8);
		// 控制字符 \0 / \uFFFF 出现在行内
		assertNoJvmCrash("data: \0\u001F\u007F\n\n".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	/** 执行读取并断言不出现 JVM 级崩溃。 */
	private static void assertNoJvmCrash(byte[] data, java.nio.charset.Charset cs) {
		AtomicInteger events = new AtomicInteger();
		try {
			SseLineReader.read(new ByteArrayInputStream(data), cs, evt -> events.incrementAndGet());
		} catch (AiException expected) {
			// IO 错误包装：允许
		} catch (StackOverflowError | OutOfMemoryError | NullPointerException
				| IndexOutOfBoundsException e) {
			fail("SseLineReader 触发 JVM 级崩溃 " + e.getClass().getName());
		}
	}
}
