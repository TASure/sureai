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

package com.sure.ai.evals.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sure.ai.evals.EvalException;

/**
 * 数据集加载器：把外部文本（CSV / JSONL）解析为不可变 {@link EvalDataset}。
 *
 * <p>零第三方依赖：UTF-8（含 BOM 容错）读取，字符级手写解析。实现类至少需实现
 * {@link #load(Reader)}；{@link #load(Path)} 与 {@link #load(InputStream)} 提供默认 UTF-8
 * 读取。IO 失败统一包装为 {@link EvalException}；单条数据行的容错策略由各实现类
 * （{@code BadLineStrategy}）自行声明。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public interface DatasetLoader {

	/**
	 * 从字符流加载数据集。
	 *
	 * @param reader 字符输入
	 * @return 数据集
	 * @throws EvalException 文件级解析失败时抛出
	 */
	EvalDataset load(Reader reader);

	/**
	 * 从字节流加载数据集（按 UTF-8 解码）。
	 *
	 * @param input 字节输入
	 * @return 数据集
	 * @throws EvalException 读取失败时抛出
	 */
	default EvalDataset load(InputStream input) {
		try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
			return load(reader);
		} catch (IOException e) {
			throw new EvalException("读取数据集字节流失败", e);
		}
	}

	/**
	 * 从文件路径加载数据集（按 UTF-8 解码）。
	 *
	 * @param path 文件路径
	 * @return 数据集
	 * @throws EvalException 读取失败时抛出
	 */
	default EvalDataset load(Path path) {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return load(reader);
		} catch (IOException e) {
			throw new EvalException("读取数据集文件失败: " + path, e);
		}
	}

	/**
	 * 读取 Reader 全文，并去掉文件开头可能出现的 UTF-8 BOM（U+FEFF）。
	 *
	 * @param reader 字符输入
	 * @return 全文文本（已去 BOM）
	 * @throws IOException 读取失败时抛出
	 */
	static String readAllText(Reader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		char[] buffer = new char[4096];
		int n;
		while ((n = reader.read(buffer)) != -1) {
			sb.append(buffer, 0, n);
		}
		String text = sb.toString();
		if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
			text = text.substring(1);
		}
		return text;
	}
}
