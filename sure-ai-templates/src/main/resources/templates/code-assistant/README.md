# {{projectName}}

代码助手模板（sureai v3.0.0 模板应用库 · code-assistant）。

## 一键运行

```bash
mvn test          # 最小单元测试（零真实网络：静态检查工具 + JSON 反序列化）
mvn exec:java     # 演示：静态检查工具直接可跑；设置 SURE_AI_OPENAI_API_KEY
                  # 后追加结构化输出（json_object）示例
```

## 代码结构

- `src/main/java/{{mainPackagePath}}/Main.java`
  - `check_code` 工具：对一段代码做纯规则静态检查（未闭合字符串 / 缩进 / 过深嵌套），
    按行返回问题清单（纯逻辑，零网络）；
  - 结构化输出演示：`responseFormat("json_object")` + `JsonMapper.fromJson`
    反序列化为 record（需 API key，无 key 时跳过并提示）。
- `src/test/java/{{mainPackagePath}}/MainTest.java`：静态检查各规则断言 +
  固定 JSON 字符串反序列化断言，零真实网络。

## 使用方式

```bash
export SURE_AI_OPENAI_API_KEY=sk-...
mvn exec:java     # 结构化输出：模型提取「代码审查结论」JSON
```

依赖：JDK 25 + Maven 3.9+；通过 import `sure-ai-bom:{{sureaiVersion}}`
统一 sureai 模块版本，聚合依赖 `sure-ai-all`。
