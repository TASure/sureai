# Command-Line Tool

> English page · Chinese source: [../cli.md](../cli.md). Module: `sure-ai-cli`.

`sure-ai-cli` lets you ask questions in the terminal with no Java code: one-shot
chat, streaming, local-document RAG, platform switching, and an interactive repl.
Runtime has zero third-party deps (JDK + `sure-ai-all`) and it compiles to a
single-file GraalVM native executable.

## Build

```bash
mvn -pl sure-ai-cli -am package -DskipTests
# fat jar: sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar
```

## Commands

```
sureai [global options] <command> [command options] [question]
```

Global options (before the subcommand): `--provider <platform>` (default `openai`),
`--api-key <key>`, `--model <model>`, `--base-url <url>`.

| Command | What it does | Example |
|---|---|---|
| `chat "question" [--once]` | multi-turn chat (v3.0.0: in-session context, in-memory rolling window of 40; `--once` single-shot; `exit`/`quit` to leave) | `sureai chat "Explain RAG in one sentence"` |
| `stream "question"` | print chunks incrementally | `sureai stream "Write a short poem"` |
| `rag "question" --doc <file>` | RAG over a local UTF-8 text file | `sureai rag "Which year was the company founded?" --doc ./company.txt` |
| `list` | list all platforms and default models | `sureai list` |
| `repl` | interactive multi-turn session (`exit`/`quit` to leave) | `sureai --provider deepseek repl` |
| `new <template> [--name dir] [--group g] [--artifact a]` | scaffold an app from the template library (v3.0.0), see [templates.md](templates.md) | `sureai new rag-kb --name my-kb` |
| `new --list` | list available templates | `sureai new --list` |
| `config [--check] [--export]` | view / validate / export `SURE_AI_*` env vars and platform readiness (v3.0.0) | `sureai config --check` |
| `doctor` | environment check: JDK version / Maven / local artifacts / platform key readiness (v3.0.0) | `sureai doctor` |
| `version` | print CLI and JDK versions (v3.0.0) | `sureai version` |
| `list` | list all platforms and default models | `sureai list` |
| `repl` | interactive multi-turn session (`exit`/`quit`) | `sureai --provider deepseek repl` |

## Examples

```bash
export SURE_AI_OPENAI_API_KEY=sk-xxx
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar chat "Explain RAG"

# streaming
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar stream "Write a poem"

# local-document RAG (no network download of the document)
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar rag "Which year?" --doc ./company.txt

# switch platform / interactive repl
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar --provider deepseek repl

# local Ollama, no key
sureai --provider ollama --model llama3.1 --base-url http://localhost:11434 chat "hello"
```

The RAG flow reads the local file → recursive-character split → in-memory vector store
→ retrieve topK=4 → inject context into the system prompt → generate. RAG needs a
platform that supports embeddings, or pass `--embedding-model` explicitly.


## v3.0.0 commands

### `new` — template library

```bash
sureai new --list                 # rag-kb / customer-service / code-assistant
sureai new rag-kb                  # generate into ./rag-kb (project name = dir name)
sureai new rag-kb --name my-kb --group com.acme --artifact kb-app
```

The generated project contains pom + main class + minimal unit tests + README and runs `mvn test` / `mvn exec:java` directly (zero network).

### `config` — environment management

```bash
sureai config                  # overview: all SURE_AI_* variables (values masked) + platform readiness
sureai config --check          # validate: list platforms missing a key (exit code 1 if any missing)
sureai config --export         # export lines, redirectable into .env
```

### `doctor` — environment check

```bash
sureai doctor
# === sureai doctor ===
# JDK version: 25.0.4.1 (meets the 25 baseline)
# Maven: Apache Maven 3.9.9 (...)
# local sure-ai-all artifact: 2.10.0-SNAPSHOT
# platform keys ready: 2/23, missing 21: [openai, ...]
```

### `version` — version info

```bash
sureai version
# sureai CLI 2.10.0-SNAPSHOT
# JDK 25.0.4.1
```

## Exit codes

| Code | Meaning |
|---|---|
| `0` | success |
| `1` | user error: missing arg, unknown platform/command, no credentials, file not found |
| `2` | upstream error: network / auth / rate-limit / server error |

## Native build (GraalVM)

The fat jar already inlines all AOT metadata, so:

```bash
native-image -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar -o sureai
```

## Next steps

- [Quick Start](./quickstart.md) · [Platforms](./platforms.md) · [RAG](./rag.md)
