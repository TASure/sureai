# JMH 性能基准

> 模块：`sure-ai-benchmark` ｜ JMH 1.37 ｜ test scope（不进运行期依赖链）

## 定位

`sure-ai-benchmark` 是独立的性能基准模块，用于量化 core 序列化/反序列化/请求体构建的 CPU 开销。**不参与常规 `mvn verify` 的测试执行**（skipTests=true），不进入 `sure-ai-all` 聚合，不影响运行期依赖。

## 依赖隔离

```
sure-ai-benchmark
├── sure-ai-core (compile)
├── jmh-core:1.37 (test)
└── jmh-generator-annprocess:1.37 (test, annotation processor)
```

- jmh 仅 test scope，`mvn dependency:tree` 确认不传递
- `jacoco.skip=true`、`spotbugs.skip=true`（基准代码不参与覆盖率/静态检查）
- 未加入 `sure-ai-all`

## 基准项

| 类 | 方法 | 测量内容 |
|---|---|---|
| `JsonBenchmark` | `jsonObjectSerialize` | 嵌套 JsonObject → toString() |
| | `jsonObjectParse` | 中等复杂度 JSON 串 → JsonObject |
| | `jsonArrayBuild` | 100 元素 JsonArray 构建 |
| `ChatRequestBenchmark` | `buildChatRequest` | Builder 构造 system+user+assistant(tool_calls)+tools+temperature |
| | `buildChatMessage` | text/image/tool 三类 ChatMessage 构造 |
| `OpenAiCompatClientBenchmark` | `serializeChatRequestBody` | ChatRequest → JSON 请求体（纯对象，无网络） |
| `SseParseBenchmark` | `parseSseStream` | SSE 行解析（~20 个 chunk） |

统一配置：`@BenchmarkMode(AverageTime)` + `@OutputTimeUnit(MICROSECONDS)` + `@Warmup(3)` + `@Measurement(5)` + `@Fork(1)`。

## 运行方式

```bash
# 1. 编译（含 JMH 注解处理器生成合成代码）
mvn -pl sure-ai-benchmark -am test-compile -P '!release'

# 2. 列出所有基准
mvn -pl sure-ai-benchmark exec:java -Dexec.args="-lp"

# 3. 运行指定基准
mvn -pl sure-ai-benchmark exec:java -Dexec.args="JsonBenchmark.* -wi 3 -i 5 -f 1 -tu us"

# 4. 运行全部基准
mvn -pl sure-ai-benchmark exec:java
```

## 结果解读

- 输出单位为微秒/操作（`-tu us`），数值越低越好
- 建议在空闲机器上运行，关闭 JIT 干扰（`-f 1` 单 fork）
- 对比不同版本时保持相同 JDK、相同 `-wi`/`-i` 参数
- 基准仅覆盖纯 CPU 路径（序列化/解析/对象构建），不含网络 IO

## 2.2.0 实测结果（已归档）

- **环境**：JDK `21.0.12.1+1-LTS`（OpenJDK 64-Bit Server VM），JMH 1.37，单线程，`-f 1`；未加额外 JVM 参数。
- **参数**：沿用类上注解 `@Warmup(3, 10s)` + `@Measurement(5, 10s)` + `@Fork(1)`（标准配置，未缩短预热/迭代）；整轮墙钟约 11 分钟。
- **运行方式**：直接 `java -cp <test-classes>+<依赖>` `org.openjdk.jmh.Main -rf json -rff docs/benchmarks/benchmark-2.2.0.json`（绕过 exec:java 的 forked classpath 限制，保证 `ForkedMain` 可加载）。
- **原始数据**：[`benchmarks/benchmark-2.2.0.json`](benchmarks/benchmark-2.2.0.json)

| 基准方法 | us/op（±误差） | ≈ 吞吐 ops/s |
|---|---|---|
| `ChatRequestBenchmark.buildChatMessage` | 0.019 ± 0.002 | ~52.3 M |
| `ChatRequestBenchmark.buildChatRequest` | 0.044 ± 0.001 | ~22.7 M |
| `JsonBenchmark.jsonObjectSerialize` | 0.993 ± 0.019 | ~1.01 M |
| `JsonBenchmark.jsonObjectParse` | 2.177 ± 0.038 | ~459 k |
| `OpenAiCompatClientBenchmark.serializeChatRequestBody` | 2.351 ± 0.229 | ~425 k |
| `SseParseBenchmark.parseSseStream` | 4.878 ± 0.313 | ~205 k |
| `JsonBenchmark.jsonArrayBuild`（100 元素） | 4.896 ± 0.162 | ~204 k |

> 口径说明：`avgt` 越低越好；ops/s 由 `1e6 / us_per_op` 换算，仅供量级参考。对象构建（Builder/ChatMessage）在亚微秒级；JSON 序列化 ~1µs、解析/请求体序列化 ~2µs；SSE 流解析与 100 元素数组构建约 5µs。纯本地计算、零网络。

