# 文档站（sure-ai-docsite）

> v3.0.0 新增。把 `docs/` 全部 Markdown 生成**可发布静态文档站**：纯 JDK 实现、
> 零第三方依赖（不用 Jekyll / npm / commonmark），输出目录可直接 push 到 gh-pages。

## 1. 快速开始

```bash
# 生成站点（默认：docs/ → target/docs-site/）
mvn -pl sure-ai-docsite package
java -jar sure-ai-docsite/target/sure-ai-docsite-*.jar
# 或
java -cp <classpath> com.sure.ai.docsite.DocSiteGenerator <docs目录> <输出目录>

# 输出目录结构
target/docs-site/
├── index.html          # 首页：分类卡片 + 全量导航
├── search.json         # 检索索引（标题/简介/正文片段）
├── <每篇文档>.html      # 渲染后的文档页（侧边全站导航 + 回首页）
├── style.css           # 内嵌样式
└── search.js           # 纯 JS 站内检索（fetch 索引过滤）
```

## 2. 渲染能力（Markdown 子集）

`MarkdownRenderer` 手写实现，覆盖常见写法：

- 标题 `#`~`######`（自动生成 slug 锚点）、段落、围栏代码块（含语言 class）
- **Mermaid 图表**：```mermaid 围栏原样输出 `<pre class="mermaid">`（配合 mermaid.js 直接可渲染）
- 行内：`code`、粗体、斜体、链接、图片
- 列表：无序/有序（同层缩进）、GFM 表格、引用块、分割线、转义字符

> 行内渲染顺序为「提取代码保护 → HTML 转义 → 替换标签 → 还原代码」，避免生成标签被二次转义。

## 3. 站点生成规则

- **分类导航**：从 `README.md` 的「## 文档索引」表格提取文档分类；未收录文档自动归入「其他」。
- **内链换算**：文档间相对链接自动换算为站点相对路径（`../README.md → index.html`、
  `x.md → x.html`，保留 `#锚点`）；外部链接与子目录链接原样保留。
- **侧边导航**：每篇页面含全站文档导航，可回首页。

## 4. 发布

```bash
# 方案一：gh-pages 分支
git checkout --orphan gh-pages && cp -r sure-ai-docsite/target/docs-site/* . && git commit

# 方案二：静态托管（GitHub Pages / Vercel / Netlify 指向输出目录即可）
```

## 5. 设计说明

- 零第三方依赖：渲染、索引、样式、检索均为 JDK 标准库 + 内嵌静态资源。
- 文档页与 README 文档索引强绑定：新增文档时更新 README 索引表即可自动归类。
- 端到端测试守护：`DocSiteGeneratorTest` 对真实 `docs/` 生成站点，断言每篇文档有对应
  `.html` 且全部站内链接目标存在。
