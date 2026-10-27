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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.templates.TemplateGenerator;
import com.sure.ai.templates.Templates;

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

	/** 版本常量（Manifest Implementation-Version 缺省时的回退值，与 pom 版本同步）。 */
	public static final String VERSION = "2.10.0-SNAPSHOT";

	/** chat 多轮会话历史上限（纯内存滚动窗口，防无界增长）。 */
	private static final int CHAT_HISTORY_LIMIT = 40;

	private final PrintStream out;
	private final ClientFactory factory;
	private final Function<String, String> fileLoader;
	private final Supplier<String> lineSource;
	private final Environment env;

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
		this(out, factory, Environment.SYSTEM, fileLoader, lineSource);
	}

	/**
	 * 扩展全参构造（v3.0.0）：注入环境变量源，供 config / doctor 零网络测试。
	 *
	 * @param out        输出流
	 * @param factory    客户端工厂
	 * @param env        环境变量源
	 * @param fileLoader 本地文档读取（路径 → 文本）
	 * @param lineSource repl 行源（EOF 返回 null）
	 */
	public CliRunner(PrintStream out, ClientFactory factory, Environment env,
			Function<String, String> fileLoader, Supplier<String> lineSource) {
		this.out = out;
		this.factory = factory;
		this.env = env;
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
			case NEW -> doNew(cmd);
			case CONFIG -> doConfig(cmd);
			case DOCTOR -> doDoctor();
			case VERSION -> doVersion();
		};
	}

	/**
	 * chat 多轮会话（v3.0.0）：默认进入会话循环、纯内存维护上下文；
	 * {@code --once} 保持单次问答行为（脚本场景）。
	 */
	private int doChat(ParsedCommand cmd) {
		Prepared prepared = factory.prepare(cmd.global());
		boolean once = "true".equals(cmd.flag("once"));
		List<ChatMessage> history = new ArrayList<>();
		history.add(ChatMessage.user(cmd.requireQuestion()));
		while (true) {
			ChatRequest request = ChatRequest.builder()
				.model(prepared.model())
				.messages(List.copyOf(history))
				.build();
			ChatResponse response = prepared.client().chat(request);
			String answer = response.firstText();
			out.println(answer);
			if (once) {
				return 0;
			}
			out.print("你 > ");
			String next = lineSource.get();
			if (next == null) {
				return 0;
			}
			String trimmed = next.trim();
			if (trimmed.isEmpty() || "exit".equals(trimmed) || "quit".equals(trimmed)) {
				return 0;
			}
			history.add(ChatMessage.assistant(answer));
			history.add(ChatMessage.user(trimmed));
			// 纯内存滚动窗口
			while (history.size() > CHAT_HISTORY_LIMIT) {
				history.remove(0);
			}
		}
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

	/**
	 * new：模板应用库生成入口（v3.0.0），对接 sure-ai-templates。
	 *
	 * <pre>sureai new &lt;模板&gt; [--name &lt;目录&gt;] [--group &lt;groupId&gt;] [--artifact &lt;artifactId&gt;]
	 * sureai new --list</pre>
	 */
	private int doNew(ParsedCommand cmd) {
		if ("true".equals(cmd.flag("list"))) {
			out.println("可用模板（sureai new <模板> [--name 目录] [--group g] [--artifact a]）：");
			for (String id : Templates.list()) {
				out.printf("  %-18s %s%n", id, Templates.of(id).displayName());
			}
			return 0;
		}
		String template = cmd.requireQuestion();
		String name = cmd.flag("name");
		Map<String, String> placeholders = new LinkedHashMap<>();
		// projectName 取输出目录的末段（工程显示名），目录本身可为任意相对/绝对路径
		String dirName = name == null || name.isBlank() ? template : name;
		Path dirPath = Path.of(dirName);
		Path fileName = dirPath.getFileName();
		String projectName = fileName != null ? fileName.toString() : dirName;
		placeholders.put("projectName", projectName);
		String group = cmd.flag("group");
		if (group != null && !group.isBlank()) {
			placeholders.put("groupId", group);
		}
		String artifact = cmd.flag("artifact");
		if (artifact != null && !artifact.isBlank()) {
			placeholders.put("artifactId", artifact);
		}
		Path target = Path.of(dirName);
		try {
			List<Path> files = TemplateGenerator.generate(template, target, placeholders);
			out.println("已生成模板工程 " + template + " → " + target.toAbsolutePath()
				+ "（" + files.size() + " 个文件）：");
			out.println("  cd " + target + " && mvn test      # 最小单元测试（零网络）");
			out.println("  cd " + target + " && mvn exec:java # 运行主类");
			return 0;
		} catch (IllegalArgumentException e) {
			throw new CliException(CliException.EXIT_USAGE, "模板生成失败：" + e.getMessage());
		} catch (IOException e) {
			throw new CliException(CliException.EXIT_API, "模板生成失败：" + e.getMessage());
		}
	}

	/**
	 * config：查看 / 导出 / 校验 SURE_AI_* 环境变量与平台可用性（v3.0.0）。
	 */
	private int doConfig(ParsedCommand cmd) {
		boolean check = "true".equals(cmd.flag("check"));
		boolean export = "true".equals(cmd.flag("export"));
		List<String> missing = new ArrayList<>();
		for (ProviderDescriptor d : ProviderRegistry.all()) {
			String key = d.envApiKey();
			if (key != null && isBlank(env.getenv(key))) {
				missing.add(d.name() + "（" + key + "）");
			}
		}
		if (export) {
			out.println("# sureai config --export（由当前环境生成，供 .env 使用）");
			for (ProviderDescriptor d : ProviderRegistry.all()) {
				String key = d.envApiKey();
				String value = key == null ? null : env.getenv(key);
				if (key != null && value != null && !value.isBlank()) {
					out.println("export " + key + "=" + quote(value));
				}
			}
			return 0;
		}
		if (check) {
			if (missing.isEmpty()) {
				out.println("全部平台 API Key 就绪。");
				return 0;
			}
			out.println("以下平台缺少 API Key（" + missing.size() + "）：");
			for (String m : missing) {
				out.println("  - " + m);
			}
			return CliException.EXIT_USAGE;
		}
		out.println("sureai config —— 环境变量（SURE_AI_*）概览：");
		List<String> names = new ArrayList<>(env.keySet());
		names.sort(String::compareTo);
		int shown = 0;
		for (String name : names) {
			if (name.startsWith("SURE_AI_")) {
				String value = env.getenv(name);
				out.println("  " + name + "=" + mask(value));
				shown++;
			}
		}
		out.println("共 " + shown + " 个 SURE_AI_* 变量；平台密钥就绪 "
			+ (ProviderRegistry.all().size() - missing.size()) + "/"
			+ ProviderRegistry.all().size()
			+ "（缺失 " + missing.size() + "）。用 --check 校验 / --export 导出。");
		return 0;
	}

	/**
	 * doctor：环境体检（v3.0.0）——JDK / Maven / 本地制品 / 平台密钥就绪度。
	 */
	private int doDoctor() {
		out.println("=== sureai doctor ===");
		String javaVersion = System.getProperty("java.version");
		out.println("JDK 版本：" + javaVersion
			+ (isJdk25(javaVersion) ? "（满足 25 基线）" : "（建议 JDK 25）"));
		out.println("JDK 主页：" + System.getProperty("java.home"));
		out.println("Maven：" + probeMaven());
		String localAll = detectLocalArtifact("sure-ai-all");
		out.println("本地 sure-ai-all 制品：" + (localAll == null ? "未安装（.m2 缺失）" : localAll));
		int ready = 0;
		List<String> missing = new ArrayList<>();
		for (ProviderDescriptor d : ProviderRegistry.all()) {
			String key = d.envApiKey();
			if (key != null && isBlank(env.getenv(key))) {
				missing.add(d.name());
			}
		}
		ready = ProviderRegistry.all().size() - missing.size();
		out.println("平台密钥就绪：" + ready + "/" + ProviderRegistry.all().size()
			+ "，缺失 " + missing.size() + " 个：" + missing);
		return 0;
	}

	/**
	 * version：打印 CLI 与 JDK 版本（v3.0.0）。
	 */
	private int doVersion() {
		String impl = CliRunner.class.getPackage().getImplementationVersion();
		out.println("sureai CLI " + (impl == null ? VERSION : impl));
		out.println("JDK " + System.getProperty("java.version"));
		return 0;
	}

	/** 探测 mvn 版本（本地进程，非网络）；失败返回「未找到」。 */
	private String probeMaven() {
		return probeMaven(new ProcessBuilder("mvn", "-v"));
	}

	/** 可注入命令的探测（测试用）：探测 mvn 版本（本地进程，非网络）；失败返回「未找到」。 */
	String probeMaven(ProcessBuilder pb) {
		try {
			Process p = pb.redirectErrorStream(true)
				.start();
			// 先限时等待，避免空输出进程阻塞输出流
			if (!p.waitFor(5, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return "（mvn -v 超时）";
			}
			try (InputStream in = p.getInputStream();
					BufferedReader reader = new BufferedReader(
						new InputStreamReader(in, StandardCharsets.UTF_8))) {
				return reader.lines()
					// 过滤 JDK25 下 mvn 的 jansi 警告行，取首个含 "Apache Maven" 的行
					.filter(l -> l.contains("Apache Maven"))
					.findFirst().orElse("（mvn -v 无版本行）");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "未找到（PATH 中无 mvn）";
		} catch (IOException e) {
			return "未找到（PATH 中无 mvn）";
		}
	}

	/** 探测本地仓库某制品的最新已安装版本（目录名）。 */
	private String detectLocalArtifact(String artifact) {
		return detectLocalArtifact(Path.of(System.getProperty("user.home"),
			".m2/repository/io/github/tasure", artifact));
	}

	/** 可注入目录的探测（测试用）：探测本地仓库某制品的最新已安装版本（目录名）。 */
	String detectLocalArtifact(Path dir) {
		if (!Files.isDirectory(dir)) {
			return null;
		}
		List<String> versions = new ArrayList<>();
		try (var stream = Files.list(dir)) {
			for (Path p : stream.filter(Files::isDirectory).toList()) {
				Path fileName = p.getFileName();
				if (fileName != null) {
					versions.add(fileName.toString());
				}
			}
		} catch (IOException e) {
			return null;
		}
		versions.sort(CliRunner::compareVersions);
		return versions.isEmpty() ? null : versions.get(versions.size() - 1);
	}

	/** 版本段感知比较：2.10.0-SNAPSHOT &gt; 2.9.0-SNAPSHOT（字典序会把 2.10 排在 2.9 前）。 */
	private static int compareVersions(String a, String b) {
		String[] as = a.split("[.-]");
		String[] bs = b.split("[.-]");
		int n = Math.min(as.length, bs.length);
		for (int i = 0; i < n; i++) {
			try {
				int ai = Integer.parseInt(as[i]);
				int bi = Integer.parseInt(bs[i]);
				if (ai != bi) {
					return Integer.compare(ai, bi);
				}
			} catch (NumberFormatException e) {
				int c = as[i].compareTo(bs[i]);
				if (c != 0) {
					return c;
				}
			}
		}
		return Integer.compare(as.length, bs.length);
	}

	private static boolean isJdk25(String version) {
		return version != null && (version.startsWith("25")
			|| version.contains("25.0") || version.contains("25-ea"));
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** 脱敏显示：仅保留前 4 位，其余掩码。 */
	private static String mask(String value) {
		if (value == null) {
			return "(未设置)";
		}
		return value.length() <= 4 ? "****" : value.substring(0, 4) + "****";
	}

	private static String quote(String value) {
		return value.matches("[A-Za-z0-9_./:=+-]+") ? value : "'" + value + "'";
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
		out.println("  chat   \"问题\" [--once] 多轮问答（--once 单次；exit/quit 退出）");
		out.println("  stream \"问题\"       流式逐片输出");
		out.println("  rag    \"问题\" --doc <文件> [--embedding-model <m>]  RAG 检索增强问答");
		out.println("  list                 列出全部平台与默认模型");
		out.println("  repl                 交互式多轮会话");
		out.println("  new    <模板> [--name 目录] [--group g] [--artifact a]  模板应用库生成");
		out.println("  new    --list        列出可用模板");
		out.println("  config [--check] [--export] 查看/校验/导出 SURE_AI_* 环境变量");
		out.println("  doctor               环境体检：JDK/Maven/依赖/平台密钥");
		out.println("  version              打印版本信息");
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
