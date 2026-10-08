# 供应链信任手册（Trust & Supply-Chain Integrity）

本页是 sureai **发布产物供应链信任链**的操作速查手册：每层信任机制「是什么 / 怎么生成 / 怎么验证 / CI 在哪跑」一页看完。
细节长文不在这里复制，统一引用 [RELEASING.md](RELEASING.md)（发布流程与命令出处）与 [../SECURITY.md](../SECURITY.md)（漏洞报告与受支持版本）。

> 面向对象：想在引入 sureai 之前审计「这个 jar 是谁签的、里面装了什么、怎么构建出来的、有没有已知漏洞」的使用者，以及需要复现/巡检信任链的维护者。

## 信任链总览

sureai 的发布制品由 **五层** 互不相干的证据背书，任何一层都可独立校验：

| 层 | 回答的问题 | 实现 | 细节 |
| --- | --- | --- | --- |
| ① GPG 签名 | 制品**是谁签的**（密钥归属） | `maven-gpg-plugin`（`-Prelease`），公钥上 keyserver | [RELEASING · 发布步骤](RELEASING.md#执行发布) |
| ② SBOM | 制品**装了什么依赖** | CycloneDX BOM（`sure-ai-all` 聚合），随 Release 归档 | [RELEASING · SBOM](RELEASING.md#sbomcyclonedx) |
| ③ SLSA 出处 | 制品**从哪个 commit、在哪条 workflow、怎么构建出来的** | GitHub 原生 artifact attestation（Sigstore keyless） | [RELEASING · SLSA](RELEASING.md#slsa--软件出处build-provenance-attestation) |
| ④ 依赖漏洞扫描 | 这些依赖**有没有已知 CVE** | OWASP dependency-check（`-Psecurity`），对 NVD | [RELEASING · 依赖扫描](RELEASING.md#依赖漏洞扫描owasp-dependency-check) |
| ⑤ 模糊健壮性 | 解析器遇到**畸形输入会不会崩** | 固定 seed 可复现的 JUnit4 FuzzTest（零新依赖） | 本文[第 5 节](#5-模糊健壮性fuzz) |

设计原则：①②③ 是**发布期**信任（随版本固化、可离线校验），④⑤ 是**开发期**质量门禁（随每次 PR 跑）。所有信任机制都是 **build 期插件 / 测试，零运行期依赖变化**——sureai 运行期唯一第三方依赖仍是 `io.github.tasure:sure-core`。

---

## 1. GPG 签名

- **是什么**：发布到 Maven Central 的每个 jar / pom / 源码包都被 GPG 私钥签名，使用者可对照公钥确认「确实来自 sureai 维护者」。
- **怎么生成**：`mvn -B -Prelease clean deploy` 时由 `maven-gpg-plugin` 自动签名；签名密钥 ID 见 [RELEASING.md](RELEASING.md#releaseyml自动发布到-ossrh-暂存)。
- **怎么验证**：用 Maven 下载后 `gpg` 校验 `.asc` 签名（公钥已上传 keyserver）。
- **CI 在哪跑**：`.github/workflows/release.yml` 打 `v*` tag 时；私钥通过 GitHub Secrets 注入。
- 注意区分：这是**发布制品签名密钥**，与[漏洞报告 PGP 信道](../SECURITY.md#pgp-联系方式)是两回事。

## 2. SBOM（CycloneDX）

- **是什么**：完整物料清单——本次发布包含哪些组件。当前聚合 SBOM 共 **31 个组件**（30 个内部 `io.github.tasure:*` 模块 + 运行期唯一第三方依赖 `sure-core`）。
- **怎么生成**：`cyclonedx-maven-plugin` 在 `sure-ai-all` 的 `package` 阶段产出 `bom.json` / `bom.xml`（见下方[命令表](#快速命令表)）。
- **怎么验证**：`cyclonedx-cli validate` 校验 schema；`grype sbom:` 直接对 SBOM 跑漏洞匹配。
- **CI 在哪跑**：`release.yml` 在 deploy 后把 `bom.json` / `bom.xml` 附加到对应 GitHub Release。
- 完整说明见 [RELEASING · SBOM](RELEASING.md#sbomcyclonedx)。

## 3. SLSA 软件出处（Build Provenance）

- **是什么**：一条可验证的「构建记录」——证明该 jar/BOM 确实是由 **TASure/sureai 这个仓库在某条 GitHub Actions workflow 上、针对某个 commit** 构建出来的，而不是被人替换的伪造制品。GitHub 托管 runner + Sigstore keyless 签名，达到 **SLSA Build L3** 参考实现级别。
- **怎么生成**：`actions/attest-build-provenance@v2` 对 SBOM + 全部模块 jar 做 DSSE 签名，上传到仓库 attestations API（无需自建签名密钥）。
- **怎么验证**：`gh attestation verify <artifact> --repo TASure/sureai`（需 GitHub CLI ≥ 2.49.0，在线校验无需自备密钥）。
- **CI 在哪跑**：`release.yml`，位于「生成 SBOM」之后、「附件到 Release」之前。
- 完整命令（含按 owner 校验、离线 `--bundle` 校验）见 [RELEASING · SLSA](RELEASING.md#slsa--软件出处build-provenance-attestation)。

## 4. 依赖漏洞扫描（OWASP dependency-check）

- **是什么**：与 SBOM 互补——SBOM 回答「依赖了什么」，本扫描回答「这些依赖有没有已知 CVE」。
- **怎么运行**：独立 `security` Maven profile，**不进默认 `mvn verify`**，命令式显式触发；门禁阈值 `failBuildOnCVSS=7`（High 及以上即失败）。当前基线：**0 个 CVE**。
- **报告位置**：`sure-ai-all/target/dependency-check/`（HTML 人读 + XML 机读）。
- **CI 在哪跑**：`ci.yml` 的 `dependency-check` job，与 build 矩阵并行，`continue-on-error: true`（NVD 抖动或新 CVE 不阻塞 PR 门禁，报告归档为 artifact 供人工复核）。
- 首次运行需联网下载 NVD 数据库（数百 MB），完整说明与可选 NVD API Key 见 [RELEASING · 依赖扫描](RELEASING.md#依赖漏洞扫描owasp-dependency-check)。

## 5. 模糊健壮性（Fuzz）

- **是什么**：对解析外部不可信输入的组件做模糊测试，保证**畸形输入只抛业务异常 `AiException`**，绝不逃逸为 JVM 级崩溃（NPE / `StackOverflowError` / `OutOfMemoryError` / `NumberFormatException`）。
- **范围**：6 个模糊测试类、共 **21 个用例**——`JsonParser` / `SseLineReader` / MCP 帧 / CLI 参数解析 / Redis RESP / FilterExpression。
- **怎么运行**：纯 JUnit4 + 固定随机种子 `Random(42)`，**零新依赖、零网络、完全可复现**；随普通 `mvn verify` 一起跑，无需特殊 profile。单独跑某一类见[命令表](#快速命令表)。
- **CI 在哪跑**：与普通单测一样在 `ci.yml` 的 build 矩阵里（`mvn -B verify`）。
- v2.1.0 通过 Fuzz 修复的真实缺陷：`JsonParser` 深嵌套 `StackOverflowError`（加 MAX_DEPTH=1000）+ 畸形数字 `NumberFormatException` 逃逸；`RedisVectorStore` RESP 长度字段 `NumberFormatException` + 超大长度 OOM / 负下标（统一收敛为 `AiException` + 上限保护）。

---

## 快速命令表

| 目的 | 命令 |
| --- | --- |
| 全量构建（含全部测试与门禁） | `mvn -B verify` |
| 生成聚合 SBOM | `mvn -B -pl sure-ai-all -am package -DskipTests -Dgpg.skip=true` → `sure-ai-all/target/bom.{json,xml}` |
| 校验 SBOM schema | `cyclonedx validate --input-file sure-ai-all/target/bom.json --input-format json --input-version v1_6 --fail-on-errors` |
| 对 SBOM 跑漏洞匹配 | `grype sbom:sure-ai-all/target/bom.json` |
| 本地依赖 CVE 扫描 | `mvn -B -DskipTests -Dgpg.skip=true install && mvn -B -Psecurity -pl sure-ai-all dependency-check:check -Dgpg.skip=true` |
| 校验某发布产物的 SLSA 出处 | `gh attestation verify sure-ai-all/target/bom.json --repo TASure/sureai` |
| 单独跑某组 Fuzz 测试 | `mvn -B -pl sure-ai-core test -Dtest=JsonParserFuzzTest` |

> 沙箱/无 OIDC 环境说明：SLSA attestation 与 CI 版 SBOM 附件依赖 GitHub Actions runner + OIDC，本地无法生成，仅可对**已发布**产物执行 `gh attestation verify` 离线/在线校验；上面命令表中生成类命令用于本地复现 SBOM 与 CVE 扫描。

## 相关文档

- 发布流程与 Secrets 配置：[RELEASING.md](RELEASING.md)
- 安全漏洞报告渠道与受支持版本：[../SECURITY.md](../SECURITY.md)
- 贡献方式与测试规范：[../CONTRIBUTING.md](../CONTRIBUTING.md)
