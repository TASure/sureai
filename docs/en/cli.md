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
| `chat "question"` | one-shot synchronous answer | `sureai chat "Explain RAG in one sentence"` |
| `stream "question"` | print chunks incrementally | `sureai stream "Write a short poem"` |
| `rag "question" --doc <file>` | RAG over a local UTF-8 text file | `sureai rag "Which year was the company founded?" --doc ./company.txt` |
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
