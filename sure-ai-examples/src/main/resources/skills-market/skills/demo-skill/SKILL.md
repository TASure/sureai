---
name: demo-skill
description: 演示技能。当用户询问如何把 Agent Skills 经 MCP 暴露时使用，展示技能发现与正文按需加载。
license: Apache-2.0
---

# Demo Skill

这是一个随 sureai examples 分发的演示技能，用于展示 **Agent Skills over MCP**
（官方扩展 `io.modelcontextprotocol/skills`）的完整链路：

1. 客户端调用 `skills/list` 发现技能清单（仅元数据，L1）；
2. 调用 `skills/get` 获取技能详情（L2）；
3. 通过 `resources/read` 读取 `skill://demo-skill/SKILL.md` 正文（L2）。

更多说明见 `references/welcome.md`（L3，按需读取）。
