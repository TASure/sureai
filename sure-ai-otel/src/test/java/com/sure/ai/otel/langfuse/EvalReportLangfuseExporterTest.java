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

package com.sure.ai.otel.langfuse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

import org.junit.Test;

/**
 * {@link EvalReportLangfuseExporter} 单测：用 JDK 内置 {@link HttpServer} 本地 mock
 * Ingestion 端点，断言 trace-create / score-create 信封、dataType=NUMERIC、value 与聚合均值一致、
 * Basic Auth 头、禁用态零请求、不可达降级，零真实网络。
 *
 * @author sureai
 * @since 2.8.0
 */
public class EvalReportLangfuseExporterTest {

	private static final String PUB = "pk-lf-test";
	private static final String SEC = "sk-lf-test";
	private static final long START = 1_700_000_000_000L;

	/** 本地 mock 服务端，捕获请求计数与最近一次请求体。 */
	private static final class MockServer {
		final HttpServer server;
		final AtomicInteger requestCount = new AtomicInteger();
		final AtomicReference<String> method = new AtomicReference<>();
		final AtomicReference<String> path = new AtomicReference<>();
		final AtomicReference<String> auth = new AtomicReference<>();
		final AtomicReference<String> body = new AtomicReference<>();

		MockServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress(0), 0);
			this.server.createContext("/api/public/ingestion", exchange -> {
				this.requestCount.incrementAndGet();
				this.method.set(exchange.getRequestMethod());
				this.path.set(exchange.getRequestURI().getPath());
				this.auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] buf = exchange.getRequestBody().readAllBytes();
				this.body.set(new String(buf, StandardCharsets.UTF_8));
				exchange.sendResponseHeaders(200, -1);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(new byte[0]);
				}
				exchange.close();
			});
			this.server.start();
		}

		String baseUrl() {
			return "http://localhost:" + this.server.getAddress().getPort();
		}

		void stop() {
			this.server.stop(0);
		}
	}

	private static EvalReport reportWithMetrics(Map<String, MetricStats> stats) {
		return EvalReport.builder()
			.datasetName("gsm8k")
			.model("gpt-4o")
			.startedAtMillis(START)
			.finishedAtMillis(START + 100_000L)
			.details(List.of(new CaseDetail("c1", List.of(), true, null, 1_000_000_000L,
				10, 5, 15, "ans", Map.of())))
			.statsByMetric(stats)
			.tokens(1000, 500, 1500)
			.failedCases(1)
			.latency(120.0, 350.0)
			.build();
	}

	/** 成功导出：trace-create + 每指标 score-create(NUMERIC)，value 与均值一致，Basic Auth 头存在。 */
	@Test
	public void testExportMapsTraceAndScores() throws Exception {
		MockServer mock = new MockServer();
		try {
			LangfuseConfig cfg = LangfuseConfig.of(mock.baseUrl(), PUB, SEC, "production");
			EvalReportLangfuseExporter exporter = new EvalReportLangfuseExporter(cfg);

			EvalReport report = reportWithMetrics(Map.of(
				"exact_match", new MetricStats("exact_match", 10, 0, 0.85, 0.0, 1.0, 8, 0.8),
				"contains", new MetricStats("contains", 10, 0, 0.9, 0.5, 1.0, 9, 0.9)));
			exporter.export(report).join();

			assertEquals(1, mock.requestCount.get());
			assertEquals("POST", mock.method.get());
			assertEquals("/api/public/ingestion", mock.path.get());
			assertNotNull(mock.auth.get());
			assertTrue(mock.auth.get().startsWith("Basic "));
			String decoded = new String(Base64.getDecoder()
				.decode(mock.auth.get().substring("Basic ".length())), StandardCharsets.UTF_8);
			assertEquals(PUB + ":" + SEC, decoded);

			String body = mock.body.get();
			assertTrue(body.startsWith("{\"batch\":["));
			// 稳定 trace id
			assertTrue("stable trace id", body.contains("\"id\":\"eval-gsm8k-" + START + "\""));
			assertTrue("trace name", body.contains("\"name\":\"eval:gsm8k\""));
			assertTrue("environment", body.contains("\"environment\":\"production\""));
			// 每指标一个 score-create 且 NUMERIC
			int scoreCount = countOccurrences(body, "\"type\":\"score-create\"");
			assertTrue("at least 2 metric scores", scoreCount >= 2);
			assertTrue("dataType NUMERIC", body.contains("\"dataType\":\"NUMERIC\""));

			// value 与聚合均值一致（浮点容差）
			assertEquals(0.85, extractScoreValue(body, "exact_match"), 1e-9);
			assertEquals(0.9, extractScoreValue(body, "contains"), 1e-9);
			// 补充 score
			assertTrue(body.contains("\"name\":\"total_tokens\""));
			assertTrue(body.contains("\"name\":\"failed_case_count\""));
			assertTrue(body.contains("\"name\":\"pass_rate\""));
		} finally {
			mock.stop();
		}
	}

	/** 均值 NaN 的指标跳过 score；其余正常。 */
	@Test
	public void testNanMetricScoreSkipped() throws Exception {
		MockServer mock = new MockServer();
		try {
			LangfuseConfig cfg = LangfuseConfig.of(mock.baseUrl(), PUB, SEC, null);
			EvalReportLangfuseExporter exporter = new EvalReportLangfuseExporter(cfg);
			EvalReport report = reportWithMetrics(Map.of(
				"exact_match", new MetricStats("exact_match", 10, 0, 0.85, 0.0, 1.0, 10, 1.0),
				"nan_metric", new MetricStats("nan_metric", 0, 3, Double.NaN, Double.NaN,
					Double.NaN, 0, Double.NaN)));
			exporter.export(report).join();

			String body = mock.body.get();
			assertTrue(body.contains("\"name\":\"exact_match\""));
			assertTrue("nan metric has no score", !body.contains("\"name\":\"nan_metric\""));
		} finally {
			mock.stop();
		}
	}

	/** 禁用配置（缺密钥）：不发任何请求，服务端 0 次命中。 */
	@Test
	public void testDisabledConfigNoRequest() throws Exception {
		MockServer mock = new MockServer();
		try {
			EvalReportLangfuseExporter exporter = new EvalReportLangfuseExporter(
				LangfuseConfig.disabled());
			exporter.export(reportWithMetrics(Map.of("exact_match",
				new MetricStats("exact_match", 1, 0, 1.0, 1.0, 1.0, 1, 1.0)))).join();
			assertEquals(0, mock.requestCount.get());
		} finally {
			mock.stop();
		}
	}

	/** report 为 null：空操作不发请求。 */
	@Test
	public void testNullReportNoRequest() throws Exception {
		MockServer mock = new MockServer();
		try {
			EvalReportLangfuseExporter exporter = new EvalReportLangfuseExporter(
				LangfuseConfig.of(mock.baseUrl(), PUB, SEC, null));
			exporter.export(null).join();
			assertEquals(0, mock.requestCount.get());
		} finally {
			mock.stop();
		}
	}

	/** 发送到不可达端口：异常被吞咽，future 正常完成，不破坏调用方。 */
	@Test
	public void testUnreachableSwallowed() {
		LangfuseConfig cfg = LangfuseConfig.of("http://localhost:1", PUB, SEC, null);
		EvalReportLangfuseExporter exporter = new EvalReportLangfuseExporter(cfg);
		exporter.export(reportWithMetrics(Map.of("exact_match",
			new MetricStats("exact_match", 1, 0, 1.0, 1.0, 1.0, 1, 1.0)))).join();
		// join 不抛即通过
	}

	/** sender 抛异常：被吞咽并记 warning，不影响调用方。 */
	@Test
	public void testSenderErrorSwallowed() {
		LangfuseBatchSender throwing = events -> {
			throw new IllegalStateException("send-failed");
		};
		EvalReportLangfuseExporter exporter = new EvalReportLangfuseExporter(true, throwing, null);
		exporter.export(reportWithMetrics(Map.of("exact_match",
			new MetricStats("exact_match", 1, 0, 1.0, 1.0, 1.0, 1, 1.0)))).join();
	}

	/** 门面返回非空导出器；null 配置为空操作。 */
	@Test
	public void testFacade() {
		assertNotNull(LangfuseExporters.evalReportExporter(LangfuseConfig.disabled()));
		assertNotNull(LangfuseExporters.evalReportExporter(null));
	}

	// ---------- 断言辅助 ----------

	private static int countOccurrences(String s, String sub) {
		int count = 0;
		int idx = 0;
		while ((idx = s.indexOf(sub, idx)) != -1) {
			count++;
			idx += sub.length();
		}
		return count;
	}

	/** 从批量 body 中正则抽取某指标 score 的 value（数值）。 */
	private static double extractScoreValue(String body, String metricName) {
		Matcher m = Pattern.compile(
			"\"name\":\"" + Pattern.quote(metricName) + "\",\"value\":([0-9.eE+-]+)")
			.matcher(body);
		assertTrue("score value not found for " + metricName, m.find());
		return Double.parseDouble(m.group(1));
	}
}
