# {{projectName}}

知识库问答模板（sureai v3.0.0 模板应用库 · rag-kb）。

## 一键运行

```bash
mvn test          # 最小单元测试（零真实网络：分块 + 关键词检索）
mvn exec:java     # 端到端演示：内置示例文档 → 分块 → 检索 →（可选）生成
```

## 代码结构

- `src/main/java/{{mainPackagePath}}/Main.java`：RAG 端到端入口
  - 离线演示（无需 API key）：本地文档 → `FixedSizeTextSplitter` 分块 →
    `KeywordRetriever` 检索 Top-K，打印命中片段与得分；
  - 设置 `SURE_AI_OPENAI_API_KEY` 后，追加把检索结果拼入上下文调用
    `OpenAiUtil.chat(...)` 生成最终答案。
- `src/test/java/{{mainPackagePath}}/MainTest.java`：分块数量/重叠窗口、
  检索 Top-K 排序断言，全部纯内存零网络。

## 使用方式

```bash
java -cp target/classes:... {{mainPackage}}.Main            # 内置示例文档
java -cp target/classes:... {{mainPackage}}.Main /path/to/doc.txt
```

依赖：JDK 25 + Maven 3.9+；通过 import `sure-ai-bom:{{sureaiVersion}}`
统一 sureai 模块版本，聚合依赖 `sure-ai-all`。
