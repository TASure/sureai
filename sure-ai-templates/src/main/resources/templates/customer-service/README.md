# {{projectName}}

客服机器人模板（sureai v3.0.0 模板应用库 · customer-service）。

## 一键运行

```bash
mvn test          # 最小单元测试（零真实网络：工具逻辑 + 参数校验）
mvn exec:java     # ReAct 演示：设置 SURE_AI_OPENAI_API_KEY 后跑完整循环；
                  # 无 key 时直接演示两个内置工具
```

## 代码结构

- `src/main/java/{{mainPackagePath}}/Main.java`：ReActAgent + 两个工具
  - `query_order`：按订单号返回 mock 订单状态（纯逻辑）；
  - `request_refund`：退换货申请（校验订单号与原因，纯规则逻辑）；
  - 会话记忆 `InMemoryConversationMemory` 注入 ReActAgent（多轮上下文），
    初始化时注入 system 提示词限定客服角色。
- `src/test/java/{{mainPackagePath}}/MainTest.java`：工具返回文本 / 参数校验
  异常路径断言，零真实网络。

## 使用方式

```bash
export SURE_AI_OPENAI_API_KEY=sk-...
mvn exec:java     # 模型驱动调用工具，多轮对话
```

依赖：JDK 25 + Maven 3.9+；通过 import `sure-ai-bom:{{sureaiVersion}}`
统一 sureai 模块版本，聚合依赖 `sure-ai-all`。
