# Documentation Site (`sure-ai-docsite`)

> New in v3.0.0. Turn all of `docs/` Markdown into a **publishable static site**: pure JDK, zero third-party dependencies (no Jekyll / npm / commonmark). The output directory can be pushed straight to gh-pages.

## 1. Quick start

```bash
# Generate the site (default: docs/ -> target/docs-site/)
mvn -pl sure-ai-docsite package
java -jar sure-ai-docsite/target/sure-ai-docsite-*.jar
# or
java -cp <classpath> com.sure.ai.docsite.DocSiteGenerator <docsDir> <outputDir>

# Output layout
target/docs-site/
├── index.html          # home: category cards + full navigation
├── search.json         # search index (title / intro / body snippets)
├── <each-doc>.html      # rendered pages (sidebar navigation + back to home)
├── style.css           # embedded styles
└── search.js           # pure-JS site search (fetch index + filter)
```

## 2. Rendering (Markdown subset)

`MarkdownRenderer` is hand-written and covers the common syntax:

- headings `#`~`######` (auto slug anchors), paragraphs, fenced code blocks (with language class)
- **Mermaid**: a ```mermaid fence is emitted as `<pre class="mermaid">` (renders with mermaid.js)
- inline: `code`, bold, italic, links, images
- lists: unordered/ordered (same-level indent), GFM tables, blockquotes, horizontal rules, escapes

> Inline rendering order is "protect code -> HTML-escape -> replace tags -> restore code", avoiding double-escaping of generated tags.

## 3. Site generation rules

- **Category navigation**: categories come from the "## 文档索引" table in `README.md`; unreferenced docs fall into "其他" (Other).
- **Link rewriting**: relative doc links are converted to site paths (`../README.md → index.html`, `x.md → x.html`, `#anchors` preserved); external links and sub-directory links stay as-is.
- **Sidebar navigation**: every page includes the full-site doc navigation and a link back to the home page.

## 4. Publishing

```bash
# Option 1: gh-pages branch
git checkout --orphan gh-pages && cp -r sure-ai-docsite/target/docs-site/* . && git commit

# Option 2: static hosting (GitHub Pages / Vercel / Netlify pointing at the output dir)
```

## 5. Design notes

- Zero third-party dependencies: rendering, index, styles, and search are JDK standard library + embedded static assets.
- Pages are tightly bound to the README doc index: adding a doc and updating the README index table categorizes it automatically.
- End-to-end tests guard the pipeline: `DocSiteGeneratorTest` generates the real `docs/` site and asserts every doc has a `.html` page and all in-site link targets exist.
