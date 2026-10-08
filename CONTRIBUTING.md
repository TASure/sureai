# Contributing to sureai

感谢你对 sureai 的关注！本文档描述如何参与贡献。

## 环境要求

- JDK 21+（推荐 Temurin 21）
- Maven 3.9+
- Git

## 快速开始

```bash
git clone https://github.com/TASure/sureai.git
cd sureai
mvn -B clean verify
```

## 代码规范

- **缩进**：Tab（不是空格），见 `.editorconfig`。
- **License 头**：所有 `.java` 和 `pom.xml` 必须带 Apache-2.0 头，可用 `mvn license:format` 自动补齐。
- **JavaDoc**：中文，类上必须标注 `@author sureai` 和 `@since <版本>`。
- **工具类**：final 类 + private 构造器（抛 `AssertionError`）。
- **导入**：禁止星号导入、未使用导入。
- checkstyle / spotbugs 在 `mvn verify` 阶段强制执行，请勿绕过。

## 测试分类与快速回归

工程使用 **JUnit4 `@Category`** 对测试做分类（不是 JUnit5 的 `@Tag`）。标记接口位于 `com.sure.ai.internal.test.tag` 包，共三个：

| 标记 | 含义 | 典型场景 |
| --- | --- | --- |
| `Unit` | 纯单元测试 | 不打注释标记时默认即 unit，照常运行 |
| `Slow` | 耗时测试 | TTL 过期、熔断器时序等待等需要 `Thread.sleep` 的用例 |
| `E2e` | 端到端测试 | MCP 子进程回环等需要拉起外部进程的用例 |

常用命令：

```bash
# 全量回归：跑全部 812 个测试（含 Slow / E2e），CI 与发版前使用
mvn -B clean verify

# 快速回归：通过 surefire -Pfast 排除 Slow / E2e，约 801 个测试，适合日常开发
mvn -B clean verify -Pfast
```

`fast` profile 在父 POM 中通过 `<excludedGroups>com.sure.ai.internal.test.tag.Slow,com.sure.ai.internal.test.tag.E2e</excludedGroups>` 实现排除；未标注任何 `@Category` 的测试默认即 unit，照常运行。

新增测试时请遵守：

- 普通纯单测**不需要**加任何 `@Category` 注解，默认就是 unit；
- 涉及真实时序等待（TTL、熔断、重试退避）的用例，类或方法上加 `@Category(Slow.class)`；
- 需要拉起子进程、真实网络回环的端到端用例，加 `@Category(E2e.class)`；
- 测试仍必须基于本地 `com.sun.net.httpserver.HttpServer` mock，**不得访问真实 AI 平台**。

## 模块开发约定

- 新增平台模块时，在父 POM `<modules>` 中注册，并在 `sure-ai-bom` / `sure-ai-all` 中添加对应依赖。
- 平台模块**只能依赖 `sure-ai-core`**，不得依赖其他平台模块或第三方库（sure-core 除外）。
- 每个平台模块必须包含：`<Pla>Client`、`<Pla>Util`（静态入口）、`<Pla>Models`（模型常量）、`package-info.java`（含官方文档链接）。
- 测试必须使用本地 `com.sun.net.httpserver.HttpServer` mock，**不得访问真实 AI 平台**。

## 提交 PR

1. Fork 仓库并创建特性分支：`git checkout -b feature/my-feature`
2. 确保 `mvn -B clean verify` 全绿。
3. 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：`feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:`。
4. 推送并创建 Pull Request，填写 PR 模板。
5. 等待 CI 通过与维护者 Review。

## 报告问题

- 使用 [Issue 模板](.github/ISSUE_TEMPLATE/) 提交 bug 或功能请求。
- Bug 报告请包含：复现代码、期望行为、实际行为、环境信息（JDK 版本、sureai 版本、平台模块）。

## 新贡献者快速上手

第一次来？按下面的闭环走一遍即可：

1. **找任务**：在 [Issues](https://github.com/TASure/sureai/issues) 筛选标签
   [`good first issue`](https://github.com/TASure/sureai/labels/good%20first%20issue)——
   这些是我们标注的低门槛任务（文档订正、补测试、小修复），通常半天内能完成。
2. **认领**：在该 issue 下留言「我来」（或直接 @维护者 assign）。为避免多人重复开工，
   **认领后再动手**；超过两周无进展，维护者可把任务释放给他人。
3. **Fork + 分支**：从你的 fork 切分支命名 `fix/xxx` / `docs/xxx` / `feat/xxx`，不要直接在 `main` 上改。
4. **改**：遵守上面的[代码规范](#代码规范)。新增功能**必须带测试**（见下文[测试要求](#新增功能的测试要求)）。
5. **本地验证**：提交前跑 `mvn -B verify`（日常可 `-Pfast` 排除 Slow/E2e），确保全绿。
6. **提 PR**：填写 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)，关联对应 issue。
7. **Review → 合并**：CI 通过 + 维护者 Approve 后由维护者合并；按 review 意见迭代即可。

## good first issue 指引

- 我们会用 `good first issue` 标签标注**适合首次贡献**的任务：通常是文档补全/订正、
  已有模块补单元测试、修复边界输入的小健壮性缺陷——不要求熟悉全库。
- 查找入口：[Issues → Labels → good first issue](https://github.com/TASure/sureai/labels/good%20first%20issue)。
- 若某个 `good first issue` 已被认领且久无更新，可在评论区 ping 维护者确认是否可接手。
- 完成第一个 PR 后，欢迎逐步挑战 `help wanted` 标签下的中等任务。

## 新增功能的测试要求

- 任何**新功能 / Bug 修复**都必须附带测试；纯文档/注释改动除外。
- 测试一律基于本地 `com.sun.net.httpserver.HttpServer` mock 或确定性随机输入，
  **不得访问真实 AI 平台、不得打真实网络**。
- 解析器 / 协议帧 / 边界输入相关改动，需保证畸形输入只抛业务异常
  `com.sure.ai.exception.AiException`，**不得**逃逸为 NPE / `StackOverflowError` /
  `OutOfMemoryError` / `NumberFormatException` 等 JVM 级错误（可参考现有 `*FuzzTest`）。
- `mvn verify` 会强制 checkstyle / spotbugs / jacoco 覆盖率门禁（core 0.70 / 其他 0.60），请勿绕过。

## 讨论与问答（Discussions）

- **问答、使用疑问、创意想法、路线图探讨**请到 [GitHub Discussions](https://github.com/TASure/sureai/discussions)，
  不要直接开 Issue——这样 Issue 队列只保留可执行的 bug 与功能请求。
- 经验分享、踩坑记录也欢迎发到 Discussions 的「Show and tell」分类。
- 安全漏洞**既不走 Issue 也不走 Discussions**，见 [SECURITY.md](SECURITY.md) 的私下报告渠道。

## 行为准则

参与本项目即表示同意 [Code of Conduct](CODE_OF_CONDUCT.md)。
