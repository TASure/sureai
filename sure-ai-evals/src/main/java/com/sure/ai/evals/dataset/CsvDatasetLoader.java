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

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.sure.ai.evals.EvalException;

/**
 * 手写 CSV 数据集加载器（零第三方依赖）。
 *
 * <p>支持：</p>
 * <ul>
 *   <li>双引号包裹字段，字段内可含逗号、换行（CRLF/LF）；</li>
 *   <li>引号内用两个连续双引号 {@code ""} 表示字面双引号；</li>
 *   <li>UTF-8 读取并容错 BOM；</li>
 *   <li>空行整体跳过。</li>
 * </ul>
 *
 * <p>表头列名（大小写不敏感、去首尾空白）到用例字段的映射：</p>
 * <ul>
 *   <li>{@code input} / {@code question} / {@code prompt} → 模型输入（作为 user 消息）；</li>
 *   <li>{@code expected} / {@code answer} / {@code reference} → 期望输出；</li>
 *   <li>{@code id} → 用例 ID（缺省时自动生成）；</li>
 *   <li>{@code tags} → 标签，按 {@code tagsSeparator} 切分（默认 {@code ;}）；</li>
 *   <li>{@code expectedTools} → 期望工具名序列，同样按分隔符切分；</li>
 *   <li>未识别列 → 整列并入用例 {@code metadata}（键为原列名）。</li>
 * </ul>
 *
 * <p>容错行为：数据行列数少于表头时缺失列补空串；多出的列以 {@code extra_<列号>} 并入 metadata；
 * 无 input 列或 input 为空的行视为坏行，按 {@link BadLineStrategy} 决定跳过（默认）或抛
 * {@link EvalException}。引号未闭合属于文件级损坏，始终抛 {@link EvalException}。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class CsvDatasetLoader implements DatasetLoader {

	/** 坏行处理策略。 */
	public enum BadLineStrategy {
		/** 跳过坏行，继续加载后续行。 */
		SKIP,
		/** 遇到坏行立即抛 {@link EvalException}。 */
		FAIL
	}

	private static final String[] INPUT_HEADERS = { "input", "question", "prompt" };
	private static final String[] EXPECTED_HEADERS = { "expected", "answer", "reference" };
	private static final String[] TOOL_HEADERS = { "expectedtools", "expected_tools" };

	private final String datasetName;
	private final String tagsSeparator;
	private final BadLineStrategy badLineStrategy;

	private CsvDatasetLoader(Builder b) {
		this.datasetName = b.datasetName;
		this.tagsSeparator = b.tagsSeparator;
		this.badLineStrategy = b.badLineStrategy;
	}

	/**
	 * 使用默认配置构造加载器（标签分隔符 {@code ;}，坏行跳过）。
	 */
	public CsvDatasetLoader() {
		this(builder());
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public EvalDataset load(Reader reader) {
		String text;
		try {
			text = DatasetLoader.readAllText(reader);
		} catch (IOException e) {
			throw new EvalException("读取 CSV 数据集失败", e);
		}
		List<List<String>> rows = parseRows(text);
		if (rows.isEmpty()) {
			return EvalDataset.builder().name(this.datasetName).build();
		}
		List<String> header = normalizeHeader(rows.get(0));
		int inputCol = findCol(header, INPUT_HEADERS);
		int expectedCol = findCol(header, EXPECTED_HEADERS);
		int idCol = header.indexOf("id");
		int tagsCol = header.indexOf("tags");
		int toolsCol = findCol(header, TOOL_HEADERS);

		List<EvalCase> cases = new ArrayList<>();
		for (int r = 1; r < rows.size(); r++) {
			List<String> row = rows.get(r);
			if (isBlankRow(row)) {
				continue;
			}
			List<String> padded = pad(row, header.size());
			String inputText = inputCol < 0 ? "" : cell(padded, inputCol);
			if (inputText.isBlank()) {
				handleBadLine(r, "第 " + (r + 1) + " 行 input 为空");
				continue;
			}
			EvalCase.Builder cb = EvalCase.builder()
				.userInput(inputText);
			if (idCol >= 0 && !cell(padded, idCol).isBlank()) {
				cb.id(cell(padded, idCol).trim());
			}
			if (expectedCol >= 0) {
				String expected = cell(padded, expectedCol);
				cb.expected(expected.isEmpty() ? null : expected);
			}
			if (tagsCol >= 0) {
				cb.tags(split(cell(padded, tagsCol)));
			}
			if (toolsCol >= 0) {
				cb.expectedTools(split(cell(padded, toolsCol)));
			}
			Map<String, Object> metadata = collectMetadata(header, padded, inputCol,
				expectedCol, idCol, tagsCol, toolsCol);
			for (int c = header.size(); c < row.size(); c++) {
				if (!row.get(c).isBlank()) {
					metadata.put("extra_" + c, row.get(c));
				}
			}
			cb.metadata(metadata);
			cases.add(cb.build());
		}
		return EvalDataset.builder()
			.name(this.datasetName)
			.cases(cases)
			.build();
	}

	private void handleBadLine(int rowIndex, String message) {
		if (this.badLineStrategy == BadLineStrategy.FAIL) {
			throw new EvalException("CSV 第 " + (rowIndex + 1) + " 行损坏: " + message);
		}
	}

	private static String cell(List<String> padded, int col) {
		return padded.get(col);
	}

	private static Map<String, Object> collectMetadata(List<String> header, List<String> row,
			int inputCol, int expectedCol, int idCol, int tagsCol, int toolsCol) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		for (int c = 0; c < header.size(); c++) {
			if (c == inputCol || c == expectedCol || c == idCol || c == tagsCol || c == toolsCol) {
				continue;
			}
			String value = row.get(c);
			if (!value.isBlank()) {
				metadata.put(header.get(c), value);
			}
		}
		return metadata;
	}

	private List<String> split(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		String[] parts = raw.split(Pattern.quote(this.tagsSeparator));
		List<String> result = new ArrayList<>();
		for (String part : parts) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

	private static List<String> pad(List<String> row, int width) {
		if (row.size() >= width) {
			return row;
		}
		List<String> padded = new ArrayList<>(row);
		while (padded.size() < width) {
			padded.add("");
		}
		return padded;
	}

	private static boolean isBlankRow(List<String> row) {
		for (String cell : row) {
			if (cell != null && !cell.isBlank()) {
				return false;
			}
		}
		return true;
	}

	private static int findCol(List<String> header, String[] names) {
		for (String name : names) {
			int idx = header.indexOf(name);
			if (idx >= 0) {
				return idx;
			}
		}
		return -1;
	}

	private static List<String> normalizeHeader(List<String> raw) {
		List<String> header = new ArrayList<>();
		for (String cell : raw) {
			header.add(cell.trim().toLowerCase());
		}
		return header;
	}

	/**
	 * 字符级 CSV 解析：支持引号包裹、引号内逗号/换行、{@code ""} 转义、CRLF。
	 *
	 * @param text 全文
	 * @return 行 × 列 矩阵
	 * @throws EvalException 引号未闭合时抛出
	 */
	static List<List<String>> parseRows(String text) {
		List<List<String>> rows = new ArrayList<>();
		List<String> field = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean inQuotes = false;
		int len = text.length();
		int i = 0;
		while (i < len) {
			char c = text.charAt(i);
			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < len && text.charAt(i + 1) == '"') {
						cell.append('"');
						i += 2;
						continue;
					}
					inQuotes = false;
					i++;
					continue;
				}
				cell.append(c);
				i++;
				continue;
			}
			if (c == '"') {
				inQuotes = true;
				i++;
				continue;
			}
			if (c == ',') {
				field.add(cell.toString());
				cell.setLength(0);
				i++;
				continue;
			}
			if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') {
					i++;
				}
				field.add(cell.toString());
				cell.setLength(0);
				rows.add(field);
				field = new ArrayList<>();
				i++;
				continue;
			}
			cell.append(c);
			i++;
		}
		if (inQuotes) {
			throw new EvalException("CSV 引号未闭合（文件损坏）");
		}
		if (!field.isEmpty() || cell.length() > 0) {
			field.add(cell.toString());
			rows.add(field);
		}
		return rows;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String datasetName = "csv-dataset";
		private String tagsSeparator = ";";
		private BadLineStrategy badLineStrategy = BadLineStrategy.SKIP;

		private Builder() {
		}

		/**
		 * 设置数据集名。
		 *
		 * @param name 名称
		 * @return this
		 */
		public Builder name(String name) {
			this.datasetName = name;
			return this;
		}

		/**
		 * 设置 tags / expectedTools 列的分隔符。
		 *
		 * @param separator 分隔符
		 * @return this
		 */
		public Builder tagsSeparator(String separator) {
			this.tagsSeparator = separator;
			return this;
		}

		/**
		 * 设置坏行处理策略。
		 *
		 * @param strategy 策略
		 * @return this
		 */
		public Builder badLineStrategy(BadLineStrategy strategy) {
			this.badLineStrategy = strategy;
			return this;
		}

		/**
		 * 构建加载器。
		 *
		 * @return 加载器
		 */
		public CsvDatasetLoader build() {
			return new CsvDatasetLoader(this);
		}
	}
}
