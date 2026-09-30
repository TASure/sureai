# GraalVM native-image 兼容说明

sureai 2.0.0「开发者体验里程碑」批次 A：全库 AOT 元数据加固。本批次**零新依赖、不改主代码**，
仅补充 GraalVM native-image 构建器自动发现的元数据资源 + 元数据完整性守卫测试。

## 为什么需要 AOT 元数据

native-image 在编译期做封闭世界分析，只保留可达类/成员；运行期反射访问的类必须提前注册。
本库有两处反射热点：

1. **`com.sure.ai.util.JsonMapper`**（core）——零依赖自研 record ↔ JSON 映射：
   - `fromJson`：`Class.getRecordComponents()` + `getDeclaredConstructor(...)` + `setAccessible(true)` + `newInstance(...)`；
   - `toJson`：`getRecordComponents()` + 组件访问器 `Method.invoke(...)`。
2. **`com.sure.ai.util.JsonSchemaGenerator`**（core）——从 record/POJO 生成 JSON Schema（MCP 工具描述等场景），
   内部走 `getRecordComponents()` / `Class.getDeclaredFields()`。

此外 core 使用 **JDK 自带 `java.net.http.HttpClient`**（HTTP/1.1 + SSE 流式），native-image 默认不打包
全部字符集与 `https/http` URL 协议提供者，需在编译参数里显式开启。

## 快速开始

> 前置：本机安装 GraalVM for JDK 21+ 并确保 `native-image` 在 PATH。

```bash
# 1) 打 fat jar（示例模块/未来 sure-ai-cli；本批次 CLI 尚未建，先以 core 单元测试验证元数据）
mvn -B -DskipTests package

# 2) native 编译（以应用 fat jar 为例；sure-ai-cli 批次交付后替换为其 jar）
native-image \
  -jar sure-ai-examples/target/sure-ai-examples-2.0.0-SNAPSHOT.jar \
  sureai-native
```

编译参数无需手写：core 模块的 `native-image.properties` 会被构建器自动合并，已含：

```
Args = -H:+AddAllCharsets --enable-url-protocols=https,http
```

- `-H:+AddAllCharsets`：SSE/HTTP 报文解析需要全字符集（不止 UTF-8）；
- `--enable-url-protocols=https,http`：JDK HttpClient 发起 https/http 请求所必需。

## 各模块元数据清单

元数据统一放在 `src/main/resources/META-INF/native-image/<groupId>/<artifactId>/`
（本库 groupId 为 `io.github.tasure`），native-image 构建器自动发现，无需手动 `-H:ReflectionConfigurationResources`。

| 模块 | reflect-config.json | resource-config.json | native-image.properties | 说明 |
|---|---|---|---|---|
| sure-ai-core | ✅ 30 条（`com.sure.ai.model` 全部 record，含嵌套 record） | ✅ 空配置（core 无内置 classpath 资源） | ✅ 全局编译参数 | 反射热点核心 |
| sure-ai-agent | ✅ 8 条（`agent.event` 下全部事件 record） | — | — | `AgentEventSseWriter` 经 `JsonMapper` 反射序列化事件 |
| sure-ai-mcp-server | ✅ 4 条（`tool` 包工具请求 record，含嵌套 `ChatToolRequest$Message`） | — | — | `JsonSchemaGenerator` 反射生成工具 JSON Schema |
| 其余全部平台模块（openai/gemini/bedrock/…） | — | — | — | **不定义 record**，响应 DTO 直接用 core record、按构造器组装，无反射访问点 |
| sure-ai-rag / sure-ai-gateway / sure-ai-mcp 等 | — | — | — | 模块内 record 均为字段直连（非反射）；`PromptTemplate` 读取的是**用户应用**自己的 classpath 资源，由应用侧注册 |

> 说明：平台模块历史上曾被抽查（openai/gemini/bedrock）确认无平台特有 record，故不为它们生成空文件；
> 若未来某平台新增 record 且经 `JsonMapper`/`JsonSchemaGenerator` 反射使用，按同目录模板补一份即可，
> `sure-ai-core` 的 `NativeImageMetadataTest` 会兜底 core model 包不遗漏。

## 已知注意事项

- **用户自定义 record**：用户用 `JsonMapper.fromJson(json, MyRecord.class)` 或
  `JsonSchemaGenerator.generate(MyRecord.class)` 反射自己的 record 时，需在应用侧的
  `META-INF/native-image/.../reflect-config.json` 中自行注册该 record（四项开关全开）。
- **SPI**：本库当前无 `META-INF/services` 服务加载，无需 `--add-opens` / ServiceLoader 元数据。
- **Quarkus 扩展**：`sure-ai-quarkus-extension` 用 `Class.forName` 按名加载 client，由 Quarkus
  deployment 模块在构建期处理，不在本批次 AOT 加固范围。
- **Spring Boot Starter**：`META-INF/spring/...AutoConfiguration.imports` 由 Spring GraalVM
  原生支持处理，本批次不重复注册。

## 验证方式

本批次不强制 CI 跑 native-image（沙箱无 GraalVM，`which native-image` 为空），
元数据正确性由 core 模块的 `com.sure.ai.util.NativeImageMetadataTest` 守卫（零真实网络、纯 JVM）：

1. `reflect-config.json` 存在且可解析；
2. classpath 扫描 `com.sure.ai.model` 包**全部 record**（含嵌套）均已注册——新增 model record 忘补元数据立即红；
3. 每个注册条目类真实存在、确为 record、`allDeclaredConstructors/allDeclaredMethods/allDeclaredFields/allRecordComponents` 四开关齐全；
4. `native-image.properties` 含 `AddAllCharsets` 与 `--enable-url-protocols`；
5. `resource-config.json` 存在且可解析。

有 GraalVM 的环境建议执行一次端到端验证：

```bash
mvn -B verify -Dgpg.skip=true
native-image -jar <应用 fat jar> /tmp/sureai-smoke
/tmp/sureai-smoke   # 冒烟：发起一次 chat（需配 API key）
```
