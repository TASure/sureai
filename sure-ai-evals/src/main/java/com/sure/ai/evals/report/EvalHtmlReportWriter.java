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
 * 自包含单文件 HTML 报告写出器：内联 CSS，无任何外链 / CDN / 脚本网络依赖。
 *
 * <p>含运行摘要、指标汇总表与每用例明细表；所有动态文本经 HTML 转义。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalHtmlReportWriter {

	private static final String CSS = """
		body{font-family:-apple-system,Segoe UI,Arial,sans-serif;margin:2rem;color:#222}
		h1{font-size:1.4rem} h2{font-size:1.1rem;margin-top:1.6rem}
		table{border-collapse:collapse;width:100%;font-size:.85rem}
		th,td{border:1px solid #ddd;padding:.35rem .6rem;text-align:left}
		th{background:#f5f5f5}
		.fail{color:#b00020} .ok{color:#1a7f37} .na{color:#888}
		""";

	/**
	 * 默认构造器。
	 */
	public EvalHtmlReportWriter() {
	}

	/**
	 * 渲染报告为自包含 HTML 字符串。
	 *
	 * @param report 报告
	 * @return HTML 文本
	 */
	public String render(EvalReport report) {
		StringBuilder sb = new StringBuilder();
		sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
			.append("<title>评估报告 - ").append(escape(report.datasetName())).append("</title>\n")
			.append("<style>").append(CSS).append("</style>\n</head>\n<body>\n");
		sb.append("<h1>评估报告：").append(escape(report.datasetName())).append("</h1>\n");
		sb.append("<p>被测模型 <code>").append(escape(report.model())).append("</code>")
			.append("；用例 ").append(report.caseCount())
			.append("；失败 ").append(report.failedCaseCount())
			.append("；总 token ").append(report.totalTokens())
			.append("；p50/p95 ").append(fmt(report.latencyP50())).append("/")
			.append(fmt(report.latencyP95())).append(" ms</p>\n");

		sb.append("<h2>指标汇总</h2>\n<table>\n<tr><th>指标</th><th>均值</th><th>通过率</th>")
			.append("<th>适用</th><th>NaN</th></tr>\n");
		for (Map.Entry<String, MetricStats> entry : report.metricStats().entrySet()) {
			MetricStats s = entry.getValue();
			sb.append("<tr><td>").append(escape(entry.getKey()))
				.append("</td><td>").append(fmt(s.mean()))
				.append("</td><td>").append(fmt(s.passRate()))
				.append("</td><td>").append(s.applicableCount())
				.append("</td><td>").append(s.nanCount())
				.append("</td></tr>\n");
		}
		sb.append("</table>\n");

		sb.append("<h2>用例明细</h2>\n<table>\n<tr><th>用例</th><th>标签</th><th>成功</th>")
			.append("<th>延迟(ms)</th><th>指标</th></tr>\n");
		for (CaseDetail detail : report.details()) {
			sb.append("<tr><td>").append(escape(detail.caseId()))
				.append("</td><td>").append(escape(String.join(",", detail.tags())))
				.append("</td><td class=\"").append(detail.succeeded() ? "ok" : "fail")
				.append("\">").append(detail.succeeded() ? "是" : "否")
				.append("</td><td>").append(String.format("%.1f", detail.latencyNanos() / 1_000_000.0))
				.append("</td><td>").append(renderMetrics(detail))
				.append("</td></tr>\n");
		}
		sb.append("</table>\n</body>\n</html>\n");
		return sb.toString();
	}

	private static String renderMetrics(CaseDetail detail) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, MetricResult> e : detail.metrics().entrySet()) {
			MetricResult r = e.getValue();
			if (sb.length() > 0) {
				sb.append("<br>");
			}
			sb.append(escape(e.getKey())).append('=');
			if (r.applicable()) {
				sb.append("<span class=\"").append(r.score() >= 1.0 ? "ok" : "")
					.append("\">").append(fmt(r.score())).append("</span>");
			} else {
				sb.append("<span class=\"na\">N/A</span>");
			}
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
		return text.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;");
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
