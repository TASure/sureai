/*
 * Copyright (c) 2026 sureai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sure.ai.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.pipeline.RagPipeline;

/**
 * CLI 主逻辑：参数解析 → 装配客户端 → 执行子命令。
 *
 * <p>与 IO 完全解耦：输出写入注入的 {@link PrintStream}，客户端由 {@link ClientFactory}
 * 注入（测试可换 Fake），文档读取与 repl 输入也均可注入，便于零网络单测。</p>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class CliRunner {

	private final PrintStream out;
	private final ClientFactory factory;
	private final Function<String, String> fileLoader;
	private final Supplier<String> lineSource;

	/**
	 * 构造：使用真实 stdout、默认文件读取与 stdin 行源。
	 *
	 * @param out     输出流
	 * @param factory 客户端工厂
	 */
	public CliRunner(PrintStream out, ClientFactory factory) {
		this(out, factory, CliRunner::readLocalFile, CliRunner::readStdinLine);
	}

	/**
	 * 全参构造（测试注入用）。
	 *
	 * @param out        输出流
	 * @param factory    客户端工厂
	 * @param fileLoader 本地文档读取（路径 → 文本）
	 * @param lineSource repl 行源（EOF 返回 null）
	 */
	public CliRunner(PrintStream out, ClientFactory factory,
			Function<String, String> fileLoader, Supplier<String> lineSource) {
		this.out = out;
		this.factory = factory;
		this.fileLoader = fileLoader;
		this.lineSource = lineSource;
	}

	/**
	 * 执行一次命令。
	 *
	 * @param args 原始命令行参数
	 * @return 进程退出码（0 成功 / 1 输入错误 / 2 API 异常）
	 */
	public int run(String[] args) {
		if (args == null || args.length == 0 || hasHelp(args)) {
			printHelp();
			return 0;
		}
		try {
			ParsedCommand cmd = ArgsParser.parse(args);
			return dispatch(cmd);
		} catch (CliException e) {
			out.println(e.getMessage());
			return e.exitCode();
		} catch (Exception e) {
			out.println("[API 异常] " + e.getMessage());
			return CliException.EXIT_API;
		}
	}

	private int dispatch(ParsedCommand cmd) {
		return switch (cmd.command()) {
			case LIST -> doList();
			case CHAT -> doChat(cmd);
			case STREAM -> doStream(cmd);
			case RAG -> doRag(cmd);
			case REPL -> doRepl(cmd);
		};
	}

	private int doChat(ParsedCommand cmd) {
		Prepared prepared = factory.prepare(cmd.global());
		String question = cmd.requireQuestion();
		ChatResponse response = prepared.client().chat(prepared.model(), question);
		out.println(response.firstText());
		return 0;
	}

	private int doStream(ParsedCommand cmd) {
		Prepared prepared = factory.prepare(cmd.global());
		String question = cmd.requireQuestion();
		ChatRequest request = ChatRequest.builder()
			.model(prepared.model())
			.messages(List.of(ChatMessage.user(question)))
			.stream(true)
			.build();
		prepared.client().chatStream(request, chunk -> {
			if (chunk.deltaText() != null) {
				out.print(chunk.deltaText());
			}
		});
		out.println();
		return 0;
	}

	private int doRag(ParsedCommand cmd) {
		String question = cmd.requireQuestion();
		String docPath = cmd.flag("doc");
		if (docPath == null || docPath.isBlank()) {
			throw new CliException(CliException.EXIT_USAGE, "rag 需要 --doc <本地文本文件>");
		}
		String text = fileLoader.apply(docPath);
		Prepared prepared = factory.prepare(cmd.global());
		if (!(prepared.client() instanceof EmbeddingClient embeddingClient)) {
			throw new CliException(CliException.EXIT_USAGE,
				"平台 " + nameOf(prepared) + " 不支持 Embedding，无法用于 RAG");
		}
		String embeddingModel = cmd.flag("embedding-model");
		if (embeddingModel == null || embeddingModel.isBlank()) {
			embeddingModel = prepared.descriptor() == null
				? null : prepared.descriptor().defaultEmbeddingModel();
		}
		if (embeddingModel == null || embeddingModel.isBlank()) {
			throw new CliException(CliException.EXIT_USAGE,
				"平台 " + nameOf(prepared) + " 无默认嵌入模型，请用 --embedding-model 指定");
		}
		RagPipeline pipeline = RagUtil.pipeline(prepared.client(), embeddingClient,
			prepared.model(), embeddingModel);
		int chunks = pipeline.ingest("cli-doc", text);
		out.println("[rag] 已摄入 " + chunks + " 个分块（topK=4）");
		ChatResponse response = pipeline.ask(question, 4);
		out.println(response.firstText());
		return 0;
	}

	private int doList() {
		out.println("sureai 可用平台（--provider 取值，括号为默认模型）：");
		for (ProviderDescriptor d : ProviderRegistry.all()) {
			out.printf("  %-12s %-22s %s%n", d.name(), d.displayName(), d.defaultModel());
		}
		out.println("共 " + ProviderRegistry.all().size() + " 个平台；用 --model 可覆盖默认模型。");
		return 0;
	}

	private int doRepl(ParsedCommand cmd) {
		Prepared prepared = factory.prepare(cmd.global());
		out.println("sureai repl（平台=" + prepared.client().name()
			+ " 模型=" + prepared.model() + "，输入 exit 或 quit 退出）");
		String line;
		while ((line = lineSource.get()) != null) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.equals("exit") || trimmed.equals("quit")) {
				break;
			}
			ChatResponse response = prepared.client().chat(prepared.model(), trimmed);
			out.println(response.firstText());
			out.println();
		}
		return 0;
	}

	private static String nameOf(Prepared prepared) {
		return prepared.descriptor() != null
			? prepared.descriptor().name() : prepared.client().name();
	}

	private static boolean hasHelp(String[] args) {
		for (String arg : args) {
			if ("--help".equals(arg) || "-h".equals(arg)) {
				return true;
			}
		}
		return false;
	}

	/** 打印帮助。 */
	public void printHelp() {
		out.println("sureai —— 命令行直接问答（零第三方依赖，支持 GraalVM native-image）");
		out.println();
		out.println("用法：sureai [全局选项] <子命令> [子命令选项] [问题]");
		out.println();
		out.println("全局选项：");
		out.println("  --provider <平台>    平台名，默认 openai（见 sureai list）");
		out.println("  --api-key <key>      API Key，缺省回退环境变量 SURE_AI_<平台>_API_KEY");
		out.println("  --model <模型>       覆盖默认模型");
		out.println("  --base-url <url>     覆盖网关地址");
		out.println();
		out.println("子命令：");
		out.println("  chat   \"问题\"       一次性同步问答");
		out.println("  stream \"问题\"       流式逐片输出");
		out.println("  rag    \"问题\" --doc <文件> [--embedding-model <m>]  RAG 检索增强问答");
		out.println("  list                 列出全部平台与默认模型");
		out.println("  repl                 交互式多轮会话");
		out.println();
		out.println("退出码：0 成功 / 1 用户输入错误 / 2 API 异常");
	}

	/** 读取本地 UTF-8 纯文本文档，IO 错误转为用法错误。 */
	private static String readLocalFile(String path) {
		try {
			return Files.readString(Path.of(path), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new CliException(CliException.EXIT_USAGE,
				"无法读取文档 " + path + ": " + e.getMessage());
		}
	}

	/** 默认 repl 行源：复用同一个 System.in 缓冲读取器，避免重复包装吞掉预读数据。 */
	private static final BufferedReader STDIN =
		new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

	/** 默认 repl 行源：System.in 读一行，EOF 返回 null。 */
	private static String readStdinLine() {
		try {
			return STDIN.readLine();
		} catch (IOException e) {
			return null;
		}
	}
}
