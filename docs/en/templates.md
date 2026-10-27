# Template Application Library (`sure-ai-templates`)

> New in v3.0.0. Generate **standalone runnable** AI application projects (pom + main class + config + README) in one shot; the result runs `mvn test` / `mvn exec:java` directly — zero third-party dependencies, zero real network.

## 1. The three built-in templates

| Template id | Scenario | Core capability | Offline demo (no key required) |
|---|---|---|---|
| `rag-kb` | Knowledge-base Q&A (RAG end-to-end) | fixed-size splitting + BM25 keyword retrieval + optional generation | local doc -> chunks -> top-K retrieval printed |
| `customer-service` | Customer-service bot | tool calling (`query_order` / `request_refund`) + ReActAgent + conversation memory | two-tool offline demo |
| `code-assistant` | Code assistant | structured output (`json_object` + JsonMapper) + static-check tool | rule engine checks unclosed quotes / indentation / long lines |

> Generated projects import `sure-ai-bom` and aggregate `sure-ai-all` (`<type>pom</type>`), so you never worry about module splits. After setting `SURE_AI_*_API_KEY` for a platform, the main class automatically adds a real model call.

## 2. How to generate

### Via the CLI (recommended)

```bash
sureai new --list                      # list available templates
sureai new rag-kb                     # generate into ./rag-kb
sureai new rag-kb --name my-kb-app    # custom output dir
sureai new rag-kb --group com.acme --artifact kb-app   # override groupId / artifactId
```

### Via the Java API

```java
// Enumerate templates
List<String> ids = Templates.list();          // ["rag-kb", "customer-service", "code-assistant"]
Templates t = Templates.of("rag-kb");          // t.id() / t.displayName()

// Generate: returns the list of created files
List<Path> files = TemplateGenerator.generate("rag-kb",
    Path.of("./my-kb-app"), Map.of("projectName", "My KB App"));
```

Placeholders (with fallbacks when omitted):

| Key | Default | Meaning |
|---|---|---|
| `projectName` | required | display name; slugified to derive the rest |
| `groupId` | `com.example` | Maven groupId |
| `artifactId` | `slug(projectName)` | Maven artifactId |
| `mainPackage` | `groupId.slug(projectName)` | Java package name |
| `mainPackagePath` | dots to slashes | source directory path |
| `sureaiVersion` | current project version | dependency version |

## 3. Generated project layout

```
my-kb-app/
├── pom.xml          # imports sure-ai-bom + sure-ai-all + JUnit 4 + exec plugin
├── README.md        # template-specific notes (project name placeholder replaced)
├── .gitignore
└── src/
    ├── main/java/<mainPackagePath>/Main.java
    └── test/java/<mainPackagePath>/MainTest.java   # minimal unit tests (zero network)
```

## 4. Acceptance

Every template ships minimal unit tests (pure in-memory logic, zero real network):

```bash
cd <generated project> && mvn test        # 3~6 assertion cases, zero network
cd <generated project> && mvn exec:java   # offline branch demo; real model calls after setting a key
```

> Template resources are packaged in `sure-ai-templates` as classpath resources, supporting both `file:` and `jar:` protocols; generation is "copy + placeholder replacement" and the target directory must not exist or be empty.

## 5. Design notes

- `TemplateGenerator` is pure JDK (standard library only), no framework.
- Generated projects depend on `sure-ai-all` (pom aggregation, `<type>pom</type>`), same assembly as the CLI.
- The generate -> compile -> test loop is guarded by `TemplateEndToEndTest` (auto-`install` when local artifacts are missing).
