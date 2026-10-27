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

package com.sure.ai.internal.json;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Random;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link JsonParser} 模糊健壮性测试（sureai v2.1.0「生产级信任」批次 1）。
 *
 * <p>零真实网络、零新依赖（仅 JUnit4 + JDK）。方法：固定种子 {@code Random(42)} 生成确定性随机输入，
 * 叠加边界枚举（空串/单字符/全符号/超长串/截断/非法转义/控制字符/超深嵌套）。
 * 断言原则：解析器只能正常返回或抛业务异常 {@link AiException}；
 * 严禁逃逸 JVM 级崩溃——{@link NullPointerException}/{@link StackOverflowError}/
 * {@link OutOfMemoryError}/{@link ArrayIndexOutOfBoundsException}/{@link NumberFormatException}。</p>
 *
 * @author sureai
 * @since 2.1.0
 */
public class JsonParserFuzzTest {

	/** 固定随机种子，保证 CI 可复现。 */
	private static final long SEED = 42L;

	/**
	 * 随机字节串 fuzz：固定种子生成 2000 段随机文本，逐条喂给解析器，只允许 AiException。
	 */
	@Test
	public void fuzzRandomStrings() {
		Random rnd = new Random(SEED);
		String[] alphabet = new String[] {
			"{", "}", "[", "]", "\"", ":", ",", "\\", "u", "0", "9", "a", "f", "t", "f", "n",
			"-", ".", "e", "E", "+", " ", "\n", "\r", "\t", "\0", "\uFFFF", "汉"
		};
		for (int round = 0; round < 2000; round++) {
			int len = 1 + rnd.nextInt(64);
			StringBuilder sb = new StringBuilder(len);
			for (int i = 0; i < len; i++) {
				sb.append(alphabet[rnd.nextInt(alphabet.length)]);
			}
			assertNoJvmCrash(sb.toString());
		}
	}

	/**
	 * 边界枚举：空串、单字符、全符号、1MB 超长串、各种截断片段。
	 */
	@Test
	public void fuzzBoundaryInputs() {
		assertNoJvmCrash("");
		assertNoJvmCrash("{");
		assertNoJvmCrash("[");
		assertNoJvmCrash("\"");
		assertNoJvmCrash("\"\\u");
		assertNoJvmCrash("\"\\u12");
		assertNoJvmCrash("\"\\uXXXX\"");
		assertNoJvmCrash("\"\\q\"");
		assertNoJvmCrash("tru");
		assertNoJvmCrash("nul");
		assertNoJvmCrash("-" );
		assertNoJvmCrash("-e");
		assertNoJvmCrash("0.1.2.3");
		assertNoJvmCrash("NaN");
		assertNoJvmCrash("Infinity");
		assertNoJvmCrash("}}}}]]]]");
		assertNoJvmCrash("{\"a\":1}{\"b\":2}");
		// 1MB 全符号串
		StringBuilder big = new StringBuilder(1024 * 1024);
		for (int i = 0; i < 1024 * 1024; i++) {
			big.append((i & 1) == 0 ? '{' : '}');
		}
		assertNoJvmCrash(big.toString());
		// 1MB 长字符串字面量
		StringBuilder longStr = new StringBuilder(1024 * 1024 + 2);
		longStr.append('"');
		for (int i = 0; i < 1024 * 1024; i++) {
			longStr.append('a');
		}
		longStr.append('"');
		assertNoJvmCrash(longStr.toString());
	}

	/**
	 * 超深嵌套：任务书要求深度 1000。解析器须有深度保护——要么正常返回、要么抛 AiException，
	 * 绝不允许 {@link StackOverflowError}。
	 */
	@Test
	public void fuzzDeepNesting() {
		// 深度 1000（任务书边界）：应优雅处理（解析或业务拒绝）
		assertNoJvmCrash(repeat("[", 1000) + repeat("]", 1000));
		assertNoJvmCrash(repeat("{\"a\":", 1000) + "1" + repeat("}", 1000));
		// 深度 50000 的恶意嵌套：必须抛 AiException，不得 StackOverflowError
		assertNoJvmCrash(repeat("[", 50000) + repeat("]", 50000));
		assertNoJvmCrash(repeat("[", 50000));
	}

	/**
	 * 合法 JSON 回归：正常输入必须能解析（防止深度保护误杀）。
	 */
	@Test
	public void wellFormedStillParses() {
		JsonElement el = Json.parse("{\"a\":[1,true,null,\"x\"],\"b\":{\"c\":\"\\u4e2d\"}}");
		assertNotNull(el);
	}

	/** 重复字符 n 次。 */
	private static String repeat(String token, int n) {
		StringBuilder sb = new StringBuilder(token.length() * n);
		for (int i = 0; i < n; i++) {
			sb.append(token);
		}
		return sb.toString();
	}

	/** 执行解析并断言不出现 JVM 级崩溃；业务异常 AiException 按契约放行。 */
	private static void assertNoJvmCrash(String input) {
		try {
			Json.parse(input);
		} catch (AiException expected) {
			// 业务语法错误：允许
		} catch (StackOverflowError | OutOfMemoryError | NullPointerException
				| ArrayIndexOutOfBoundsException | NumberFormatException | NegativeArraySizeException e) {
			fail("JsonParser 触发 JVM 级崩溃 " + e.getClass().getName()
				+ "，输入前 120 字符: " + abbrev(input));
		}
	}

	/** 截断输入用于报错信息。 */
	private static String abbrev(String s) {
		return s.length() <= 120 ? s : s.substring(0, 120);
	}
}
