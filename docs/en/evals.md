# Evals — LLM evaluation pipeline

> English page · Chinese source: [../evals.md](../evals.md). Module: `sure-ai-evals` (v2.8.0).

sureai ships a generic evaluation module: dataset → per-case model run → per-metric
scoring → aggregated report → threshold assertion → record/replay regression. It depends
only on `sure-ai-core` (zero third-party runtime deps) and is complementary to the
RAG-specific evaluator in `sure-ai-rag`.

## Minimal example (offline-friendly)

```java
EvalDataset dataset = EvalDataset.of("qa-smoke", List.of(
        EvalCase.of("c1", "What is the capital of France?", "Paris"),
        EvalCase.of("c2", "What is 1+1?", "2")));

try (EvalRunner runner = EvalRunner.builder(chatClient, "under-test-model")
        .addMetric(ExactMatchMetric.builder().build())
        .addMetric(LlmJudgeMetric.builder(judgeClient, "judge-model")
                .mode(JudgeMode.BINARY).build())
        .build()) {
    EvalReport report = runner.run(dataset);

    System.out.println(new EvalMarkdownReportWriter().render(report));
    EvalAssertions.assertMeets(report, EvalThreshold.builder()
            .putMean("exact_match", 0.5)
            .putPassRate("llm_judge", 0.6)
            .build());   // throws AssertionError when below the bar (CI gate)
}
```

`EvalUtil.run(client, model, dataset, metrics...)` is the one-stop shortcut.

## Datasets

`EvalCase` fields: `id`, `input` (`List<ChatMessage>`, multi-turn), `expected`
(reference answer, nullable), `tags`, `metadata`, `expectedTools`. Load from CSV or
JSONL (hand-written parsers, UTF-8 with BOM tolerance, quoted fields, bad-line
SKIP/FAIL):

```java
EvalDataset ds = CsvDatasetLoader.builder().name("qa")
        .badLineStrategy(CsvDatasetLoader.BadLineStrategy.SKIP).build()
        .load(Path.of("qa.csv"));
```

CSV header aliases (case-insensitive): `input|question|prompt`,
`expected|answer|reference`, `id`, `tags`, `expectedTools`; unknown columns go to
`metadata`. JSONL takes one JSON object per line, `input` as string or a message array.

## Metrics

`EvalMetric` SPI: `name()`, `evaluate(case, outcome, context)`, `passThreshold()`
(default 1.0). Scores are normalized to [0,1]; `Double.NaN` means "not applicable"
and is excluded from aggregation. A metric throwing is caught by the runner and
recorded as `notApplicable` — it never aborts the batch.

Built-in metrics:

| Metric | Notes |
|---|---|
| `LlmJudgeMetric` | LLM-as-judge, four `JudgeMode`: BINARY (YES→1/NO→0), SCALE_1_TO_5 → `(n-1)/4`, SCORE_100 → `n/100`, PAIRWISE (A wins 1.0 / tie 0.5 / B wins 0.0). Rubric template placeholders `{input}/{expected}/{actual}`; judge failure / unparseable output → NaN by default, or a fixed default score via `onUnparseableDefaultScore(x)`. Default threshold 0.6. |
| `ExactMatchMetric` | normalized whitespace + optional ignoreCase; missing `expected` → NaN. |
| `ContainsMetric` | substring must appear; `delimiter` splits multiple needles, `allRequired` toggles all-vs-any, optional ignoreCase. |
| `RegexMetric` | `expected` is treated as a regex; `FIND` (default) or `MATCHES`; invalid regex scores 0 instead of throwing. |
| `JsonSchemaValidityMetric` | validates the output JSON against a target class schema (required fields + type/enum checks); score = passed checks / checked. |
| `EmbeddingSimilarityMetric` | cosine similarity via `EmbeddingClient`; pass threshold default 0.8; missing output / zero-norm vector / embed failure → NaN. |
| `LatencyMetric` | `ms ≤ target` → 1.0, `≥ limit` → 0.0, linear decay between; default 1000/3000 ms. |
| `TokenCostMetric` | total-token proxy (no real currency pricing); default 500/2000 tokens; missing usage → NaN. |

## Runner, reports, assertions

`EvalRunner` runs cases sequentially by default; `.concurrency(n)` uses an internal
fixed thread pool (closed by the runner). Per-case model failures and per-metric
exceptions are isolated. The `EvalReport` carries per-case details, per-metric
`MetricStats` (mean / min / max / passRate / applicable / NaN counts), total tokens,
failed cases, latency p50/p95.

Three writers: `EvalJsonReportWriter` (NaN → JSON null), `EvalMarkdownReportWriter`,
`EvalHtmlReportWriter` (self-contained, inline CSS, no external links).
`EvalThreshold` + `EvalAssertions.assertMeets(report, threshold)` gate mean / passRate
per metric; missing or all-NaN metrics count as failures.

## Record / replay regression (golden dataset)

```java
Path golden = Path.of("target/golden.jsonl");
EvalReport baseline;
try (RecordingClient rec = RecordingClient.record(realClient, golden);
        EvalRunner r = EvalRunner.builder(rec, "my-model")
                .addMetric(ExactMatchMetric.builder().build()).build()) {
    baseline = r.run(dataset);
}
EvalReport current;
try (RecordingClient replay = RecordingClient.replay(golden);   // zero network
        EvalRunner r = EvalRunner.builder(replay, "my-model")
                .addMetric(ExactMatchMetric.builder().build()).build()) {
    current = r.run(dataset);
}
TraceRegression.compare(baseline, current).assertPassed();  // default tolerance 0.02
```

`RecordingClient.record(delegate, path)` appends one JSONL line per chat;
`RecordingClient.replay(path)` matches requests (model + messages) in order and
needs no network. `TraceRegression` compares metric means baseline vs current and
flags degradation beyond the tolerance (global or per-metric); cases align by `caseId`.

## Observability

- **OTel**: `OtelSupport.evalTracing(openTelemetry).record(report)` maps one run to an
  `eval.evaluation` span (`gen_ai.evaluation.*` attributes; ERROR status with a
  low-cardinality `error.type` on failed cases or sub-1.0 pass rate). Null provider →
  no-op.
- **Langfuse**: `LangfuseExporters.evalReportExporter(LangfuseConfig.fromEnv())`
  exports one trace + NUMERIC `score-create` per metric (plus `pass_rate`,
  `total_tokens`, `failed_case_count`); missing credentials → no-op.

## A/B comparison

`EvalComparison.of(reportA, reportB)` gives per-metric mean diffs (`meanDiffs()`) and
per-case-per-metric pairwise wins/ties (`winsA()`, `ties()`, `winsB()`).

## RAG evals vs generic evals

Generic conversation quality / latency / cost → this module; RAG faithfulness /
context precision-recall → `sure-ai-rag` `RagEvaluator` (see [../rag.md](../rag.md)).
They share core's `AiClient` model and are independent.
