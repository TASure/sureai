## 变更类型

- [ ] Bug 修复
- [ ] 新功能
- [ ] 文档更新
- [ ] 重构 / 性能优化
- [ ] 其他（请描述）

## 变更内容

简要描述本次 PR 做了什么。

## 背景 / 动机

为什么要改？解决什么问题？（大改动请说明设计取舍；若是修复 issue，说明根因。）

## 关联 Issue

Closes #（如适用）

## 测试

- [ ] `mvn -B clean verify` 全绿（checkstyle / spotbugs / jacoco / license 均通过）
- [ ] 新增/修改了单元测试
- [ ] 测试覆盖率不低于门禁（core 0.70 / 其他 0.60）
- [ ] 若涉及解析器/协议帧/边界输入，已考虑模糊健壮性（不得逃逸 NPE / SOE / OOM / NFE）

## 自检清单

- [ ] 所有 .java / pom.xml 带有 Apache-2.0 license 头
- [ ] Tab 缩进，无星号导入，无未使用导入
- [ ] 中文 JavaDoc，类上标注 `@author sureai` 和 `@since`
- [ ] 平台模块仅依赖 sure-ai-core，未引入其他第三方依赖
- [ ] 测试使用本地 HttpServer mock，无真实网络调用
- [ ] 用户可见的行为/API 变化已同步更新对应 `docs/*.md` 与 README（中英文）
- [ ] 已在 `CHANGELOG.md` 顶部 `[Unreleased]` 段追加条目
