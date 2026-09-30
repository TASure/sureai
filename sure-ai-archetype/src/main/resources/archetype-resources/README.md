# ${artifactId}

基于 [sureai](https://github.com/TASure/sureai) 的最小 Java 工程，使用 **${platform}** 平台完成一次对话。

## 前置要求

- JDK 21+
- 为 ${platform} 平台申请 API Key

## 配置 API Key

按平台设置环境变量（本工程生成时平台为 `${platform}`）：

```bash
export SURE_AI_${platform.toUpperCase()}_API_KEY=你的key
```

## 运行

```bash
# 编译
mvn -q compile

# 直接运行（需本地已有 exec-maven-plugin，或联网首次下载）
mvn exec:java
```

或手动：

```bash
mvn -q package
java -cp target/${artifactId}-${version}.jar ${package}.Main
```

## 依赖说明

工程通过 `dependencyManagement` import `io.github.tasure:sure-ai-bom:${sureaiVersion}` 统一版本，
仅依赖单平台模块 `io.github.tasure:sure-ai-${platform}`（传递引入 `sure-ai-core`），无其他第三方依赖。

## 支持的平台

openai / azure / anthropic / gemini / deepseek / qwen / zhipu / moonshot / doubao / baidu /
ollama / grok / mistral / llamacpp / cohere / bedrock / minimax / stepfun / baichuan / lingyi /
siliconflow / hunyuan / spark。
