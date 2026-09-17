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

## 行为准则

参与本项目即表示同意 [Code of Conduct](CODE_OF_CONDUCT.md)。
