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

## 已知问题与规避（v1.1.0 实测）

- **JVM 内 DNS 解析失败**：沙箱/容器内 `getent` 可解析 `ossrh-staging-api.central.sonatype.com`，但 JVM 进程偶发/持续 `UnknownHostException`（间歇性，重试不一定恢复）。规避：把当前解析出的 ELB IP 写入自定义 hosts 文件并让 Maven 使用：
  ```bash
  # 多写几个 ELB IP 提高容错（getent hosts ossrh-staging-api.central.sonatype.com 可多次取样）
  printf "52.89.234.26 ossrh-staging-api.central.sonatype.com\n100.23.248.255 ossrh-staging-api.central.sonatype.com\n35.83.166.6 ossrh-staging-api.central.sonatype.com\n35.163.244.112 ossrh-staging-api.central.sonatype.com\n" > /tmp/sonatype-hosts
  export MAVEN_OPTS="-Djdk.net.hosts.file=/tmp/sonatype-hosts"
  ```
  无需 root、不影响其他命令；`-Djdk.net.hosts.file` 是 JDK 内置机制（JDK 11+）。
- deploy 失败重跑幂等（会新建 staging repo，不污染已关闭 repo）；长等待类命令拆后台/分多条执行。
