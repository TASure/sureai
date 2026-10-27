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
package com.sure.ai.evals.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.sure.ai.evals.metric.MetricResult;

/**
 * Markdown 报告写出器：汇总表（每指标均值 / 通过率 / NaN 数）+ 失败明细 + 每用例指标表。
 *
 * <p>表格单元格中的 {@code |} 与换行均做转义，避免破坏 Markdown 表格结构。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalMarkdownReportWriter {

	/**
	 * 默认构造器。
	 */
	public EvalMarkdownReportWriter() {
	}

	/**
	 * 渲染报告为 Markdown 字符串。
	 *
	 * @param report 报告
	 * @return Markdown 文本
	 */
	public String render(EvalReport report) {
		StringBuilder sb = new StringBuilder();
		sb.append("# 评估报告：").append(report.datasetName()).append("\n\n");
		sb.append("- 被测模型：`").append(report.model()).append("`\n");
		sb.append("- 用例数：").append(report.caseCount())
			.append("，失败：").append(report.failedCaseCount()).append('\n');
		sb.append("- 总 token：").append(report.totalTokens())
			.append("（prompt ").append(report.totalPromptTokens())
			.append(" / completion ").append(report.totalCompletionTokens()).append("）\n");
		sb.append("- 延迟 p50/p95：").append(fmt(report.latencyP50())).append(" / ")
			.append(fmt(report.latencyP95())).append(" ms\n\n");

		sb.append("## 指标汇总\n\n");
		sb.append("| 指标 | 均值 | 通过率 | 适用 | NaN | 最小 | 最大 |\n");
		sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
		for (Map.Entry<String, MetricStats> entry : report.metricStats().entrySet()) {
			MetricStats s = entry.getValue();
			sb.append("| ").append(entry.getKey())
				.append(" | ").append(fmt(s.mean()))
				.append(" | ").append(fmt(s.passRate()))
				.append(" | ").append(s.applicableCount())
				.append(" | ").append(s.nanCount())
				.append(" | ").append(fmt(s.min()))
				.append(" | ").append(fmt(s.max()))
				.append(" |\n");
		}
		sb.append('\n');

		if (!report.failedCaseDetails().isEmpty()) {
			sb.append("## 失败用例\n\n");
			for (CaseDetail detail : report.failedCaseDetails()) {
				sb.append("- **").append(detail.caseId()).append("**：")
					.append(escape(detail.errorMessage())).append('\n');
			}
			sb.append('\n');
		}

		sb.append("## 用例明细\n\n");
		sb.append("| 用例 | 标签 | 成功 | 延迟(ms) | 指标 |\n");
		sb.append("| --- | --- | --- | --- | --- |\n");
		for (CaseDetail detail : report.details()) {
			sb.append("| ").append(escape(detail.caseId()))
				.append(" | ").append(escape(String.join(",", detail.tags())))
				.append(" | ").append(detail.succeeded() ? "✅" : "❌")
				.append(" | ").append(String.format("%.1f", detail.latencyNanos() / 1_000_000.0))
				.append(" | ").append(escape(renderMetrics(detail)))
				.append(" |\n");
		}
		return sb.toString();
	}

	private static String renderMetrics(CaseDetail detail) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, MetricResult> e : detail.metrics().entrySet()) {
			MetricResult r = e.getValue();
			if (sb.length() > 0) {
				sb.append("；");
			}
			sb.append(e.getKey()).append('=')
				.append(r.applicable() ? fmt(r.score()) : "N/A");
		}
		return sb.toString();
	}

	private static String fmt(double value) {
		return Double.isNaN(value) ? "N/A" : String.format("%.3f", value);
	}

	private static String escape(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("\\", "\\\\")
			.replace("|", "\\|")
			.replace("\r", " ")
			.replace("\n", " ");
	}

	/**
	 * 写出到文件。
	 *
	 * @param report 报告
	 * @param path   目标路径
	 */
	public void write(EvalReport report, Path path) {
		try {
			Files.writeString(path, render(report));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
