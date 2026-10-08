# 发布指南

本文档描述 sureai 发布到 Maven Central 的流程。

## 前置条件

1. [Sonatype OSSRH](https://central.sonatype.com/) 账号，且对 `io.github.tasure` groupId 有发布权限。
2. GPG 密钥对，已上传公钥到 keyserver。
3. `~/.m2/settings.xml` 配置：

```xml
<settings>
  <servers>
    <server>
      <id>ossrh</id>
      <username>你的OSSRH用户名</username>
      <password>你的OSSRH密码</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>release</id>
      <properties>
        <gpg.keyname>你的GPG密钥ID</gpg.keyname>
      </properties>
    </profile>
  </profiles>
</settings>
```

## 发布步骤

### 1. 准备发布

```bash
# 确保全量构建通过
mvn -B clean verify

# 更新版本号（去掉 -SNAPSHOT）
mvn versions:set -DnewVersion=0.1.0
mvn versions:commit
```

### 2. 更新 CHANGELOG

将 `CHANGELOG.md` 中 `[0.1.0-SNAPSHOT]` 改为 `[0.1.0] - YYYY-MM-DD`，并新增 `[Unreleased]` 段落。

### 3. 提交并打 Tag

```bash
git add -A
git commit -m "release: 0.1.0"
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin main --tags
```

### 4. 执行发布

```bash
mvn -B -Prelease clean deploy
```

`release` profile 会激活：
- `maven-source-plugin`：附加源码包
- `maven-javadoc-plugin`：附加 JavaDoc 包
- `maven-gpg-plugin`：GPG 签名所有制品
- `nexus-staging-maven-plugin`：上传到 OSSRH 暂存仓库

### 5. 在 Sonatype 关闭并发布

1. 登录 [https://oss.sonatype.org/](https://oss.sonatype.org/)
2. 进入 "Staging Repositories"
3. 选中本次上传的暂存仓库，点击 "Close"
4. 等待规则校验通过（约 1-2 分钟）
5. 点击 "Release" 发布到 Maven Central

### 6. 恢复开发版本

```bash
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT
mvn versions:commit
git add -A
git commit -m "chore: bump version to 0.2.0-SNAPSHOT"
git push origin main
```

## 注意事项

- **严禁**在 CI 或日常构建中执行 `mvn deploy`，仅在手动发布时使用。
- 发布前确保 `mvn -B clean verify` 全绿（含 checkstyle / spotbugs / jacoco / license）。
- JavaDoc 必须无错误（`mvn -Prelease javadoc:javadoc` 预检查）。
- 发布后约 10-30 分钟可在 Maven Central 搜索到。

## SBOM（CycloneDX）

sureai 通过 `cyclonedx-maven-plugin`（2.9.1，仅 build 期插件，**零运行期依赖变化**）在 `sure-ai-all` 聚合模块的 `package` 阶段生成完整 CycloneDX BOM。

- **聚合点**：`sure-ai-all`（pom 包，依赖全部平台模块）。其依赖树即完整 SBOM：30 个内部模块（`io.github.tasure:*`）+ 唯一运行期第三方依赖 `io.github.tasure:sure-core:0.2.0`；测试作用域依赖（junit）已排除。
- **版本集中管理**：父 `pom.xml` 的 `<pluginManagement>` 声明插件版本；实际绑定只在 `sure-ai-all/pom.xml`，不污染其他模块的 verify 链。

### 本地生成

```bash
# 生成聚合 SBOM（上游模块需已 install 到本地 .m2；首次可加 -am）
mvn -B -pl sure-ai-all -am package -DskipTests -Dgpg.skip=true

# 产物：
#   sure-ai-all/target/bom.xml   (CycloneDX XML)
#   sure-ai-all/target/bom.json  (CycloneDX JSON，CI/扫描优先使用)
```

插件同时把 BOM 附加为 Maven 构件（`sure-ai-all-<version>-cyclonedx.xml` / `.json`），随 `mvn deploy` 一并上传到 OSSRH。

### 校验

```bash
# 1) CycloneDX schema 合法性校验（cyclonedx-cli，见 https://github.com/CycloneDX/cyclonedx-cli）
cyclonedx validate --input-file sure-ai-all/target/bom.json --input-format json --input-version v1_6 --fail-on-errors
cyclonedx validate --input-file sure-ai-all/target/bom.xml  --input-format xml  --input-version v1_6 --fail-on-errors

# 2) 漏洞扫描（grype，自动识别 CycloneDX；见 https://github.com/anchore/grype）
grype sbom:sure-ai-all/target/bom.json
```

### CI 行为

`.github/workflows/release.yml` 在 `mvn -Prelease clean deploy` 成功后，额外执行：

1. `mvn -B -pl sure-ai-all package -DskipTests -Dgpg.skip=true`（确保 BOM 新鲜）；
2. 通过 `softprops/action-gh-release@v2` 把 `bom.json` + `bom.xml` 附加到当前 `v*` tag 对应的 GitHub Release（需 `permissions: contents: write`）。

> 注：本轮仅改动 workflow 定义，不实际触发发布；Release 附件在真正打 tag 推送时才会生成。

## SLSA / 软件出处（Build Provenance Attestation）

与 GPG 签名、SBOM 并列，构成发布产物的**三层供应链信任链**：

| 层 | 回答的问题 | 实现 |
| --- | --- | --- |
| GPG 签名 | 产物是谁签的（密钥归属） | `maven-gpg-plugin`（`-Prelease`），公钥上 keyserver |
| SBOM | 产物里装了什么依赖、有没有 CVE | CycloneDX BOM（见上节）+ OWASP dependency-check |
| **SLSA Provenance** | **产物是从哪个源码 commit、在哪个 CI workflow 上、怎么构建出来的** | GitHub 原生 artifact attestation（`actions/attest-build-provenance@v2` + Sigstore keyless） |

第三层在 `.github/workflows/release.yml` 的 `Generate SBOM` 之后、`Attach SBOM to Release` 之前新增一步：

```yaml
- name: Attest build provenance (SLSA v1.0)
  uses: actions/attest-build-provenance@v2
  with:
    subject-path: |
      sure-ai-all/target/bom.json
      sure-ai-all/target/bom.xml
      sure-ai-*/target/*.jar
```

- **被签名的 subject**：聚合 SBOM（`sure-ai-all/target/bom.json` + `bom.xml`）以及全部模块 jar（`sure-ai-*/target/*.jar`；jar 分散在各平台模块 `target/`，聚合点 `sure-ai-all` 是 pom 包、不出 jar）。`subject-path` 官方支持 glob / 路径列表（总数 ≤ 1024），本仓库模块数远低于上限。
- **签名机制**：action 用 workflow 的 OIDC token 向 GitHub 换取**短期 Sigstore 签名证书**，对一条 in-toto/v1 格式的 SLSA Build Provenance statement 做 DSSE 签名，随后上传到**本仓库的 attestations API**。全程**无需自建/保管签名密钥**（Sigstore 公钥基础设施托管，public repo 走 Sigstore public-good instance）。
- **所需权限**：workflow 的 `permissions` 在原有 `contents: write` 之外新增 `id-token: write`（取 OIDC）与 `attestations: write`（上传 attestation）。
- **SLSA 级别**：该 action 生成 `https://slsa.dev/provenance/v1` predicate；在 GitHub 托管 runner（`ubuntu-latest`）上由本 action 构建，属于 SLSA Build Track 的可信构建者，对应 **SLSA Build L3** 保障（GitHub 官方将 artifact attestations 作为 SLSA L3 的参考实现）。

### 验证（消费方 / 发布后自检）

前置：`gh auth login`，且 **GitHub CLI ≥ 2.49.0**（`gh attestation` 子命令自此版本引入；老版本先升级 `gh`）。attestation 由 GitHub 托管，在线校验无需自备密钥：

```bash
# 1) 在线校验某个产物（用 --repo 精确到仓库，安全保证最强）
gh attestation verify sure-ai-all/target/bom.json --repo TASure/sureai

# 2) 按 owner 范围校验（粒度更宽）
gh attestation verify path/to/some.jar --owner TASure

# 3) 输出完整校验结果（含签名证书、时间戳、provenance predicate），供人读/策略引擎消费
gh attestation verify sure-ai-all/target/bom.json --repo TASure/sureai --format json

# 4) 只看 provenance predicate 内容（jq 过滤）
gh attestation verify sure-ai-all/target/bom.json --repo TASure/sureai --format json \
  --jq '.[].verificationResult.statement.predicate'
```

- 默认即校验 predicate 类型 `https://slsa.dev/provenance/v1`，并校验签名证书里的 `SourceRepository` / `SourceRepositoryOwner` / SAN（即「确实是 TASure/sureai 这个仓库的 Actions 签的」）。
- 如需更严，可加 `--signer-workflow <owner>/<repo>/<path>/workflow.yml` 限定签名 workflow、`--source-ref <tag>` 限定源码 ref、`--deny-self-hosted-runners` 拒绝自托管 runner。
- **离线 / 气隙校验**：把 bundle 落到本地后用 `--bundle`（`-b`）指定，配合 `--custom-trusted-root`：
  ```bash
  gh attestation verify path/to/artifact.jar --repo TASure/sureai --bundle sha256:<digest>.jsonl
  ```

> 来源：GitHub Docs《Using artifact attestations to establish provenance for builds》<https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations/using-artifact-attestations-to-establish-provenance-for-builds>；`gh attestation verify` 手册 <https://cli.github.com/manual/gh_attestation_verify>；离线校验 <https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations/verifying-attestations-offline>。

> 注：本步依赖 GitHub Actions 环境 + OIDC token，沙箱内无法实跑（无 runner / 无 OIDC），仅交付 workflow 配置与上述验证命令；真正打 `v*` tag 推送后才会生成 attestation。attestation 生成后可在仓库 Actions 标签页 / `gh attestation verify` 中看到。

## 依赖漏洞扫描（OWASP dependency-check）

与上面的 SBOM 互补：SBOM 回答「**我们依赖了什么**」，OWASP dependency-check 回答「**这些依赖有没有已知 CVE**」。本扫描通过独立的 `security` Maven profile 接入，**仅 build 期插件，零运行期依赖变化**。

- **插件**：`org.owasp:dependency-check-maven:12.2.2`（挂在父 `pom.xml` 的 `<profile id="security">`，默认不激活）。版本核实：<https://mvnrepository.com/artifact/org.owasp/dependency-check-maven>（12.2.2 为 12.x 成熟补丁线；13.0.0 为 2026-08 新主版本，暂不追新）。
- **命令式调用，不进默认 verify**：插件**不绑定任何生命周期阶段**，必须显式 `-Psecurity` 并用命令式 goal 调用。日常 `mvn -B verify` 不激活该 profile，主构建速度与行为完全不变。
- **扫描聚合点**：`sure-ai-all`（其依赖树即完整模块链 + 唯一运行期第三方依赖 `sure-core`）；`skipTestScope=true` 跳过 test 作用域（junit）降噪。

### 本地运行

```bash
# 1) 先把全部模块 install 到本地 .m2（sure-ai-all 依赖内部 SNAPSHOT，否则聚合点解析不到依赖树）
mvn -B -DskipTests -Dgpg.skip=true install

# 2) 执行扫描（在聚合点 sure-ai-all 上跑 check goal）
mvn -B -Psecurity -pl sure-ai-all dependency-check:check -Dgpg.skip=true
```

- **报告位置**：`sure-ai-all/target/dependency-check/`
  - `dependency-check-report.html` —— 人读报告，浏览器直接打开查看每个命中依赖的 CVE 列表与详情链接。
  - `dependency-check-report.xml` —— 同结果的 XML，供程序化消费/归档。
- **门禁阈值 `failBuildOnCVSS=7`**：扫描出 **CVSS ≥ 7.0（High 及以上）** 的漏洞时 goal 返回非零、构建失败；< 7.0 仅在报告中标注。（插件默认 11=永不失败，本项目收紧到 7。）
- **数据源与首跑耗时**：插件首次运行会从 NVD（NIST）拉取 CVE/CPE 本地数据库（约数百 MB，落在 `~/.m2/repository/org/owasp/dependency-check-data/`），并同步 CISA KEV（已知被利用漏洞清单）；**首次较慢（可能数分钟）**，后续按 `nvdValidForHours`（默认 4h）增量更新。需要稳定联网；离线环境无法扫描。
- **可选：NVD API Key**：匿名调用 NVD 限速较严（每次请求间隔约 8s）。在 <https://nvd.nist.gov/developers/request-an-api-key> 免费申请后，通过命令行传入可显著提速：
  ```bash
  mvn -B -Psecurity -pl sure-ai-all dependency-check:check -DnvdApiKey=你的NVD_API_KEY
  ```
- **已关闭的 analyzer**：OSS Index（Sonatype Guide）现已强制鉴权，匿名会报错；本工程运行期零第三方依赖，NVD 本地库 + KEV 已足够，故在 profile 中设 `ossIndexAnalyzerEnabled=false`，避免对外部鉴权服务的依赖。

### CI 行为

`.github/workflows/ci.yml` 新增 `dependency-check` job（与 `build` 矩阵**并行**）：先 `mvn -B -DskipTests install` 安装全模块，再 `mvn -B -Psecurity -pl sure-ai-all dependency-check:check`，最后用 `actions/upload-artifact@v4` 把 `sure-ai-all/target/dependency-check/` 归档为 artifact `dependency-check-report`。

该 job 设 `continue-on-error: true`：NVD 数据库下载抖动、或某条新披露 CVE 触发 `failBuildOnCVSS=7` 时，只让该 job 标红，**不阻塞 build 矩阵的 PR 门禁**；报告随 artifact 归档供人工复核。待基线稳定（长期无 High 级命中）后，可把 `continue-on-error` 改为 `false` 以强制执行门禁。

## Javadoc（聚合 API 文档）托管到 GitHub Pages

sureai 通过独立的 `javadoc` workflow 把**全模块聚合 Javadoc** 发布到 GitHub Pages，供开发者在线查阅各平台模块的公开 API。零运行期依赖变化（仅 build 期 `maven-javadoc-plugin`，版本与 `release` profile 同为 3.6.3）。

- **聚合点是【仓库根父 POM】，不是 `sure-ai-all`**：`maven-javadoc-plugin:aggregate` 聚合的是 **reactor 中的 Maven 项目**。在根目录执行（不带 `-pl`），根 POM 的 `<modules>`（约 40 个模块）全部进入 reactor，插件一次性生成一套合并文档（含 `overview-summary.html` / `allclasses-index.html` / `overview-tree.html`）。
  > ⚠️ 切勿写成 `mvn -pl sure-ai-all javadoc:aggregate`：`sure-ai-all` 是 **pom 包、自身无一行源码**，对它聚合只会得到一个空目录（实测约 10 秒「成功」但无任何类）。本仓库既往「先 install 再 `-pl sure-ai-all`」的用法适用于依赖树类目标（如 dependency-check、SBOM），**不适用于** `javadoc:aggregate`。
- **为什么必须先 install**：聚合 Javadoc 要解析每个模块的 compile classpath，其中跨模块引用（`sure-ai-*` 之间）在 standalone 调 `aggregate` 时不会自动带 `target/classes`，须先把全量模块装进本地 `.m2`。这与 ci.yml 的 dependency-check job、benchmark.yml「先 install 再聚合」的先例一致。

### 本地生成与预览

```bash
# 1) 先把全部模块 install 到本地 .m2（聚合 Javadoc 解析跨模块类路径所需）
mvn -B -DskipTests -Dgpg.skip=true install

# 2) 在【仓库根目录】执行聚合 Javadoc（全限定坐标锁版本，避免本机是否激活 release profile 的差异）
mvn -B org.apache.maven.plugins:maven-javadoc-plugin:3.6.3:aggregate -Dgpg.skip=true

# 3) 产物（仓库根，不是 sure-ai-all 下）：
#    target/site/apidocs/index.html
# 浏览器打开本地预览：
#    open target/site/apidocs/index.html   # macOS
#    xdg-open target/site/apidocs/index.html  # Linux
```

- **doclint 现状**：保持插件默认 doclint。当前源码在默认 doclint 下仅有 *missing* 类**警告**（缺 `@param` / `@return` / 默认构造注释），**不阻断构建**；历史 v1.9.0 的畸形 HTML doclint **错误**已在源码层修复，故无需 `-Ddoclint=none`。
- **发布前预检查**：日常发布前仍按上文「注意事项」跑 `mvn -Prelease javadoc:javadoc` 预检查单模块 javadoc 无错误。

### 在线地址

- workflow 部署后，站点为 GitHub Pages 默认 URL：`https://<owner>.github.io/<repo>/`。
  - 对本仓库（`TASure/sureai`）即 **`https://tasure.github.io/sureai/`**——**以仓库 Settings → Pages 开启后的实际生成地址为准**，本文不臆造子路径。
- **启用一次性前置**（沙箱无法代做，需仓库管理员）：仓库 **Settings → Pages → Build and deployment → Source 选 "GitHub Actions"**。首次部署成功后 GitHub 才会分配上述 URL。
- 触发：push 到 `main`（文档随主干常新）或 Actions 页手动 `workflow_dispatch`；打 `v*` tag 的发布文档由 release 流程覆盖，本 workflow 不重复绑 tag。

### 备选：javadoc.io

制品发布到 Maven Central 后，[javadoc.io](https://www.javadoc.io/) 会**自动**抓取每个上传的 `-javadoc.jar`，按 `https://www.javadoc.io/doc/io.github.tasure/<artifactId>/<version>/` 提供浏览，**无需任何 workflow**。它是按「单制品 + 具体版本」粒度；GitHub Pages 这边则提供「跨全部模块、随主干滚动更新」的聚合视图。二者互补：Pages 看最新主干全貌，javadoc.io 查某个已发布版本的单个制品。

## CI / GitHub Actions 自动化

除上述手动流程外，仓库根目录 `.github/workflows/` 下提供了四条 workflow，分别承担发布、性能基线、常规 CI 门禁与聚合 Javadoc 托管。手动发布流程仍然有效（例如沙箱内需要用 hosts 文件规避 DNS 问题时），CI 流程只是把相同的 `mvn -B -Prelease clean deploy` 搬到 runner 上自动执行。

### release.yml（自动发布到 OSSRH 暂存）

- **文件**：`.github/workflows/release.yml`
- **触发条件**：
  - push tag：`v*`（即 `git tag v1.4.0 && git push origin v1.4.0`）；
  - 手动触发：Actions 页面 `workflow_dispatch`。
- **执行命令**：`mvn -B -Prelease clean deploy -Dgpg.keyname="$KEYNAME" -Dgpg.passphrase="${{ secrets.GPG_PASSPHRASE }}"`
  （`KEYNAME` 默认取项目 GPG 密钥 `D4CB857FBF6D720F38F26062350F235EAF989176`，可用 `GPG_KEY_ID` secret 覆盖。注意：`-Dgpg.keyname=` 传空会导致 gpg 报 `Invalid user ID` 签名失败，勿留空。）
- **需要配置的 Repository Secrets**（GitHub Repository → Settings → Secrets and variables → Actions；未配置时 workflow 会在 `Validate release secrets` 步骤显式报错）：

  | Secret | 说明 |
  | --- | --- |
  | `OSSRH_USERNAME` | Sonatype 账号用户名（https://central.sonatype.com/）。 |
  | `OSSRH_PASSWORD` | Sonatype 账号密码 / token。 |
  | `GPG_PRIVATE_KEY` | GPG 私钥，`gpg --armor --export-secret-keys <KEYID>` 输出全文，含 `-----BEGIN PGP PRIVATE KEY BLOCK-----` 头尾。 |
  | `GPG_PASSPHRASE` | GPG 私钥口令。 |
  | `GPG_KEY_ID`（可选） | 用于签名的密钥长 ID（默认项目密钥，无需配置；更换密钥时才需要）。 |

- runner 通过 `actions/setup-java@v5` 的 `server-id: ossrh` 直接把用户名/密码写入 `~/.m2/settings.xml` 的 `<server id="ossrh">`，与父 POM `distributionManagement` / nexus-staging 插件配置的 serverId 对齐；同时导入 GPG 私钥。
- 与手动发布一样，CI 上传到 OSSRH 后 `autoReleaseAfterClose=false`，仍需登录 Sonatype 控制台手动 **Close + Release**（见上文第 5 步）。
- deploy 成功后，CI 依次执行：(1) 生成 CycloneDX SBOM；(2) `actions/attest-build-provenance@v2` 对 SBOM + 全部模块 jar 生成 SLSA Build Provenance 签名 attestation（上传仓库 attestations API）；(3) 通过 `softprops/action-gh-release@v2` 把 `bom.json` / `bom.xml` 附加到当前 tag 的 GitHub Release（详见上文 [SBOM（CycloneDX）](#sbomcyclonedx) 与 [SLSA / 软件出处](#slsa--软件出处build-provenance-attestation) 两节）。为此 workflow 的 `permissions` 在 `contents: write`（Release 附件）之外，另加 `id-token: write`（OIDC）与 `attestations: write`（上传 attestation）。

### benchmark.yml（JMH 性能基线）

- **文件**：`.github/workflows/benchmark.yml`
- **触发条件**：
  - 手动触发：Actions 页面 `workflow_dispatch`；
  - 定时任务：每月 1 号 UTC 00:00（cron `0 0 1 * *`）。
- **注意**：JMH 运行前先 `mvn install -pl sure-ai-benchmark -am` 把父 POM 与全部上游安装到本地仓库（不能只 `-pl sure-ai-core`，否则解析 `sure-ai-benchmark` 时会在 Central 找不到尚未发布的父 POM / 新版本模块而失败）。
- **不进默认 PR/push 门禁**（耗时长），仅按需运行。
- **执行内容**：先 `mvn -B install -DskipTests -pl sure-ai-core`，再 `mvn -B -pl sure-ai-benchmark exec:java@jmh-main`，以最小预热/迭代参数（`-wi 1 -i 1 -bm avgt`）跑 JMH，结果输出到 `sure-ai-benchmark/target/benchmark-result.json`，通过 `actions/upload-artifact@v4` 归档为名为 `benchmark-result` 的 artifact，便于跨版本对比趋势。

### ci.yml（多 OS / 多 JDK 矩阵）

- **文件**：`.github/workflows/ci.yml`
- **触发条件**：push / pull request 到 `main`。
- **矩阵**：
  - JDK 21：`ubuntu-latest` + `macos-latest`，并额外 include 一个 `windows-latest`（`continue-on-error: true`，Windows 路径分隔符/脚本行为尚未在 CI 全量验证，失败不阻断整体）；
  - JDK 25：仅 `ubuntu-latest`（通过 `exclude` 把 JDK25 + macos 排除掉，节省资源）。
- 每个 job 执行 `mvn -B verify`；JDK21 的 job 额外上传 `sure-ai-*/target/site/jacoco/` 作为 JaCoCo 覆盖率 artifact。
- 另有一个**与 build 矩阵并行**的 `dependency-check` job（见上文 [依赖漏洞扫描（OWASP dependency-check）](#依赖漏洞扫描owasp-dependency-check)），独立跑 OWASP 扫描并归档 HTML/XML 报告，`continue-on-error: true`，不进 `mvn verify`、不拖慢主门禁。

### javadoc.yml（聚合 Javadoc → GitHub Pages）

- **文件**：`.github/workflows/javadoc.yml`
- **触发条件**：push 到 `main`（文档随主干常新）；手动触发：Actions 页面 `workflow_dispatch`。
- **执行步骤**：
  1. `actions/checkout@v4` + `actions/setup-java@v5`（temurin 21，`cache: maven`）；
  2. `mvn -B -DskipTests -Dgpg.skip=true install`（先把全量模块装进本地 `.m2`）；
  3. `mvn -B org.apache.maven.plugins:maven-javadoc-plugin:3.6.3:aggregate -Dgpg.skip=true`（**根目录**聚合，产物 `target/site/apidocs/`；全限定坐标锁版本，见上文 [Javadoc 托管](#javadoc聚合-api-文档托管到-github-pages)）；
  4. `actions/configure-pages@v5` → `actions/upload-pages-artifact@v3`（`path: target/site/apidocs`）→ `actions/deploy-pages@v4`。
- **权限**：`contents: read`（checkout）+ `pages: write` + `id-token: write`（GitHub 原生 Pages 部署，keyless，无需自建/保管 token）；job 挂 `github-pages` environment。
- **前置**：仓库 Settings → Pages → Source 选 **GitHub Actions**（一次性，见上文）。沙箱内无法实跑 Pages 部署（需 GitHub 环境 + Pages 开启），仅交付 workflow 定义；真正 push 到 main 后才会生成线上站点。
- **与现有 ci/release 互不影响**：独立文件、独立触发，不改 ci.yml / release.yml，零新增第三方 Action。

## 已知问题与规避（v1.1.0 实测）

- **JVM 内 DNS 解析失败**：沙箱/容器内 `getent` 可解析 `ossrh-staging-api.central.sonatype.com`，但 JVM 进程偶发/持续 `UnknownHostException`（间歇性，重试不一定恢复）。规避：把当前解析出的 ELB IP 写入自定义 hosts 文件并让 Maven 使用：
  ```bash
  # 多写几个 ELB IP 提高容错（getent hosts ossrh-staging-api.central.sonatype.com 可多次取样）
  printf "52.89.234.26 ossrh-staging-api.central.sonatype.com\n100.23.248.255 ossrh-staging-api.central.sonatype.com\n35.83.166.6 ossrh-staging-api.central.sonatype.com\n35.163.244.112 ossrh-staging-api.central.sonatype.com\n" > /tmp/sonatype-hosts
  export MAVEN_OPTS="-Djdk.net.hosts.file=/tmp/sonatype-hosts"
  ```
  无需 root、不影响其他命令；`-Djdk.net.hosts.file` 是 JDK 内置机制（JDK 11+）。
- deploy 失败重跑幂等（会新建 staging repo，不污染已关闭 repo）；长等待类命令拆后台/分多条执行。
