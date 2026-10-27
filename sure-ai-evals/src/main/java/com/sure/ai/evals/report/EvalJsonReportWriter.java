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
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.evals.metric.MetricResult;

/**
 * JSON 报告写出器（零依赖，复用 core 内置 {@link Json} 构建）。
 *
 * <p>输出结构：运行元信息 → 汇总（用例数 / 失败数 / token / 延迟分位数）→
 * 每指标统计（均值 / 通过率 / NaN 数）→ 每用例每指标明细。NaN 分数序列化为 JSON null。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalJsonReportWriter {

	/**
	 * 默认构造器。
	 */
	public EvalJsonReportWriter() {
	}

	/**
	 * 渲染报告为 JSON 字符串。
	 *
	 * @param report 报告
	 * @return JSON 文本
	 */
	public String render(EvalReport report) {
		JsonObject root = Json.object();
		root.put("datasetName", report.datasetName());
		root.put("model", report.model());
		root.put("startedAtMillis", report.startedAtMillis());
		root.put("finishedAtMillis", report.finishedAtMillis());

		JsonObject summary = Json.object();
		summary.put("caseCount", report.caseCount());
		summary.put("failedCases", report.failedCaseCount());
		summary.put("latencyP50Ms", report.latencyP50());
		summary.put("latencyP95Ms", report.latencyP95());
		JsonObject tokens = Json.object();
		tokens.put("prompt", report.totalPromptTokens());
		tokens.put("completion", report.totalCompletionTokens());
		tokens.put("total", report.totalTokens());
		summary.set("tokens", tokens);
		root.set("summary", summary);

		JsonObject metrics = Json.object();
		for (Map.Entry<String, MetricStats> entry : report.metricStats().entrySet()) {
			MetricStats stats = entry.getValue();
			JsonObject m = Json.object();
			m.put("applicable", stats.applicableCount());
			m.put("nan", stats.nanCount());
			m.put("passCount", stats.passCount());
			putDouble(m, "mean", stats.mean());
			putDouble(m, "min", stats.min());
			putDouble(m, "max", stats.max());
			putDouble(m, "passRate", stats.passRate());
			metrics.set(entry.getKey(), m);
		}
		root.set("metrics", metrics);

		JsonArray cases = Json.array();
		for (CaseDetail detail : report.details()) {
			JsonObject c = Json.object();
			c.put("caseId", detail.caseId());
			JsonArray tags = Json.array();
			for (String tag : detail.tags()) {
				tags.add(tag);
			}
			c.set("tags", tags);
			c.put("succeeded", detail.succeeded());
			if (detail.errorMessage() != null) {
				c.put("error", detail.errorMessage());
			}
			c.put("latencyNanos", detail.latencyNanos());
			c.put("actualText", detail.actualText());
			JsonObject usage = Json.object();
			usage.put("promptTokens", detail.promptTokens());
			usage.put("completionTokens", detail.completionTokens());
			usage.put("totalTokens", detail.totalTokens());
			c.set("usage", usage);
			JsonObject resultMetrics = Json.object();
			for (Map.Entry<String, MetricResult> e : detail.metrics().entrySet()) {
				JsonObject r = Json.object();
				if (e.getValue().applicable()) {
					r.put("score", e.getValue().score());
				} else {
					r.set("score", null);
				}
				r.put("reason", e.getValue().reason());
				resultMetrics.set(e.getKey(), r);
			}
			c.set("metrics", resultMetrics);
			cases.add(c);
		}
		root.set("cases", cases);
		return Json.stringify(root);
	}

	private static void putDouble(JsonObject object, String key, double value) {
		if (Double.isNaN(value)) {
			object.set(key, null);
		} else {
			object.put(key, value);
		}
	}

	/**
	 * 写出到文件。
	 *
	 * @param report 报告
	 * @param path   目标路径
	 * @throws UncheckedIOException 写出失败时抛出
	 */
	public void write(EvalReport report, Path path) {
		try (Writer writer = Files.newBufferedWriter(path)) {
			writer.write(render(report));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
