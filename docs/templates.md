# 模板应用库（sure-ai-templates）

> v3.0.0 新增。一键生成**可独立运行**的 AI 应用工程（pom + 主类 + 配置 + README），
> 生成后直接 `mvn test` / `mvn exec:java`，零第三方依赖、零真实网络。

## 1. 三个内置模板

| 模板 id | 场景 | 核心能力 | 离线演示（无 Key 也可跑） |
|---|---|---|---|
| `rag-kb` | 知识库问答（RAG 端到端） | 固定大小分块 + BM25 关键词检索 + 可选生成 | 本地文档 → 分块 → 检索 Top-K 打印 |
| `customer-service` | 客服机器人 | 工具调用（查单/退换）+ ReActAgent + 会话记忆 | `query_order` / `request_refund` 两个工具演示 |
| `code-assistant` | 代码助手 | 结构化输出（`json_object` + JsonMapper）+ 静态检查工具 | 规则引擎检查未闭合引号/缩进/超长行 |

> 模板工程通过 `sure-ai-bom` import + `sure-ai-all`（pom 聚合）引入全部库能力，
> 用户无需关心模块拆分。设置对应平台 `SURE_AI_*_API_KEY` 后，主类自动追加真实模型调用。

## 2. 生成方式

### 方式一：CLI（推荐）

```bash
sureai new --list                      # 查看可用模板
sureai new rag-kb                     # 生成到 ./rag-kb
sureai new rag-kb --name my-kb-app    # 指定输出目录
sureai new rag-kb --group com.acme --artifact kb-app   # 覆盖 groupId / artifactId
```

### 方式二：Java API

```java
// 枚举模板
List<String> ids = Templates.list();          // ["rag-kb", "customer-service", "code-assistant"]
Templates t = Templates.of("rag-kb");          // t.id() / t.displayName()

// 生成工程：返回生成的全部文件路径
List<Path> files = TemplateGenerator.generate("rag-kb",
    Path.of("./my-kb-app"), Map.of("projectName", "My KB App"));
```

占位符（未提供时自动兜底）：

| 键 | 缺省值 | 说明 |
|---|---|---|
| `projectName` | 必填 | 工程显示名，slug 化后派生其余命名 |
| `groupId` | `com.example` | Maven groupId |
| `artifactId` | `slug(projectName)` | Maven artifactId |
| `mainPackage` | `groupId.slug(projectName)` | Java 包名 |
| `mainPackagePath` | 点转斜杠 | 源文件目录路径 |
| `sureaiVersion` | 当前工程版本 | 依赖版本 |

## 3. 生成工程结构

```
my-kb-app/
├── pom.xml          # import sure-ai-bom + sure-ai-all + JUnit 4 + exec 插件
├── README.md        # 模板专属说明（含占位符替换后的工程名）
├── .gitignore
└── src/
    ├── main/java/<mainPackagePath>/Main.java
    └── test/java/<mainPackagePath>/MainTest.java   # 最小单元测试（零网络）
```

## 4. 验收

每个模板自带最小单元测试（纯内存逻辑，零真实网络）：

```bash
cd <生成工程> && mvn test        # 3~6 个断言用例，零网络
cd <生成工程> && mvn exec:java   # 离线分支演示；设 Key 后追加真实模型调用
```

> 模板资源以 classpath 资源形式打包在 `sure-ai-templates` 内，支持 `file:` 与 `jar:` 两种加载
> 协议；生成过程为「复制 + 占位符替换」，目标目录须不存在或为空。

## 5. 设计说明

- `TemplateGenerator` 零第三方依赖（JDK 标准库），单文件无框架。
- 模板工程依赖 `sure-ai-all`（pom 聚合，`<type>pom</type>`），与 CLI 同装配方式。
- 生成 → 编译 → 测试闭环由 `TemplateEndToEndTest` 守护（工程根缺本地制品时自动 `install` 补装）。
