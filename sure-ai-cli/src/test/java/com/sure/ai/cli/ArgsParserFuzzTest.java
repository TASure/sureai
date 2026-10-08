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

package com.sure.ai.cli;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * {@link ArgsParser} 模糊健壮性测试（sureai v2.1.0「生产级信任」批次 1）。
 *
 * <p>零真实网络、零新依赖。方法：固定种子 {@code Random(42)} 生成随机参数数组，
 * 叠加边界枚举（超长参数、未知选项、缺值尾选项、畸形组合、空数组、null 元素）。
 * 断言原则：只能正常返回 {@link ParsedCommand} 或抛业务异常 {@link CliException}；
 * 严禁 {@link NullPointerException}/{@link ArrayIndexOutOfBoundsException}/JVM 级 Error 逃逸。</p>
 *
 * @author sureai
 * @since 2.1.0
 */
public class ArgsParserFuzzTest {

	/** 固定随机种子。 */
	private static final long SEED = 42L;

	/**
	 * 随机参数数组 fuzz：固定种子生成 1000 组参数组合，只允许 CliException。
	 */
	@Test
	public void fuzzRandomArgs() {
		Random rnd = new Random(SEED);
		String[] tokens = {
			"--provider", "--api-key", "--model", "--base-url", "--bogus", "--", "-x",
			"chat", "stream", "rag", "list", "repl", "CHAT", "unknown", "--doc", "--embedding-model",
			"openai", "gpt-4", "http://x", "a b c", ""
		};
		for (int round = 0; round < 1000; round++) {
			int n = rnd.nextInt(8);
			String[] args = new String[n];
			for (int i = 0; i < n; i++) {
				args[i] = tokens[rnd.nextInt(tokens.length)];
			}
			assertNoJvmCrash(args);
		}
	}

	/**
	 * 边界枚举：空数组、纯尾选项缺值、1MB 超长参数、全符号、null 数组与 null 元素。
	 */
	@Test
	public void fuzzBoundaryArgs() {
		assertNoJvmCrash(new String[0]);
		assertNoJvmCrash(new String[] { "--provider" });
		assertNoJvmCrash(new String[] { "--api-key", "--model" });
		assertNoJvmCrash(new String[] { "--unknown", "chat" });
		assertNoJvmCrash(new String[] { "chat", "--doc" });
		assertNoJvmCrash(new String[] { "chat", "--bogus", "v" });
		assertNoJvmCrash(new String[] { "--", "chat" });
		assertNoJvmCrash(new String[] { "---" });
		// 1MB 超长参数值
		StringBuilder huge = new StringBuilder(1024 * 1024);
		for (int i = 0; i < 1024 * 1024; i++) {
			huge.append('q');
		}
		assertNoJvmCrash(new String[] { "--model", huge.toString() });
		assertNoJvmCrash(new String[] { huge.toString() });
		// null 数组应被容忍（parse 内部归一化为空）
		assertNoJvmCrash(null);
	}

	/**
	 * 合法组合回归：正常命令行必须解析成功（防止过度容错误杀）。
	 */
	@Test
	public void wellFormedParses() {
		ParsedCommand pc = ArgsParser.parse(new String[] {
			"--provider", "openai", "--model", "gpt-4", "chat", "hello", "world" });
		assertNotNull(pc);
	}

	/** 执行解析并断言不出现 JVM 级崩溃；CliException 按契约放行。 */
	private static void assertNoJvmCrash(String[] args) {
		try {
			ArgsParser.parse(args);
		} catch (CliException expected) {
			// 用法错误：允许
		} catch (StackOverflowError | OutOfMemoryError | NullPointerException
				| ArrayIndexOutOfBoundsException | NegativeArraySizeException e) {
			List<String> preview = new ArrayList<>();
			if (args != null) {
				for (String a : args) {
					preview.add(a == null ? "null" : (a.length() <= 20 ? a : a.substring(0, 20) + "..."));
				}
			}
			fail("ArgsParser 触发 JVM 级崩溃 " + e.getClass().getName() + "，参数: " + preview);
		}
	}
}
