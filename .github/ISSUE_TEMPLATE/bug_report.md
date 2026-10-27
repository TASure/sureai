---
name: Bug Report
about: 报告一个缺陷或异常行为
title: "[Bug] "
labels: ["bug"]
assignees: []

---

> [!IMPORTANT]
> 如果你报告的是**安全漏洞**（远程可利用、凭据泄露、签名绕过等），**请勿在此公开提单**，
> 请按 [SECURITY.md](https://github.com/TASure/sureai/blob/main/SECURITY.md) 的私下渠道邮件联系维护者，避免公开披露前细节外泄。

## 问题描述

清晰描述遇到的问题。遇到了什么？与预期的差异是什么？

## 复现步骤

1. 引入的依赖（请填写**实际出问题**的坐标与版本）：
```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>2.0.0</version>
</dependency>
```

2. 最小复现代码（请尽量精简到能稳定复现）：
```java
// 最小可复现代码
```

3. 执行结果 / 异常堆栈：
```
// 粘贴完整异常堆栈（请勿包含真实 API Key）
```

## 期望行为

描述你期望发生什么。

## 实际行为

描述实际发生了什么（与期望行为的差异点）。

## 环境信息

- JDK 版本：`java -version` 完整输出
- Maven 版本：`mvn -v` 输出
- sureai 版本：
- 平台模块：（如 `sure-ai-openai`）
- 操作系统：
- 是否开启了可选模块（gateway / rag / agent / spring-boot-starter 等）：
