# Security Policy

## 支持的版本

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅        |

## 报告安全漏洞

如果你发现了安全漏洞，请**不要**公开创建 Issue。请通过以下方式私下联系维护者：

- 邮箱：`335069951@qq.com`
- 邮件主题请标注 `[sureai SECURITY]`

我们会在 48 小时内确认收到，并在合理时间内提供修复计划。

## 安全最佳实践（使用方）

- API Key 请勿硬编码在源码中，使用环境变量 `SURE_AI_<PLATFORM>_API_KEY` 注入。
- 不要将包含真实 API Key 的日志或异常堆栈提交到公开仓库。
- `sure-ai-baidu` 的 access_token 缓存在内存中，进程重启后自动重新获取，无需持久化。
- `sure-ai-zhipu` 的 JWT 在模块内签名，secret 不会离开进程内存。
- 本项目不持久化任何请求或响应数据，所有 HTTP 交互为无状态。

## 依赖安全

- 运行期唯一第三方依赖为 `io.github.tasure:sure-core`，版本在父 POM `dependencyManagement` 中锁定。
- CI 通过 dependabot 自动监控依赖更新。
- 发布前执行 `mvn -Prelease verify` 生成 SBOM（CycloneDX）。
