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

package com.sure.ai.evals.trace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.evals.EvalException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.model.ToolCall;
import com.sure.tool.lang.Assert;

/**
 * 录制 / 回放对话客户端：装饰 {@link AiClient}，支撑 golden dataset 工作流。
 *
 * <p><b>录制模式</b>（{@link #record(AiClient, Path)} 等）：每次 {@link #chat(ChatRequest)}
 * 先调用委托客户端，再把请求 / 响应成对序列化为<b>一行 JSONL</b> 追加落盘（UTF-8），
 * 供后续离线重放。落盘失败抛 {@link EvalException}，不静默吞 IO 错误。</p>
 *
 * <p><b>回放模式</b>（{@link #replay(Path)} 等）：<b>不持有委托客户端、零网络调用</b>；
 * 按「请求匹配优先、录制顺序兜底」返回录制的 {@link ChatResponse}——先用 model + 消息
 * （role/content/toolCallId/toolCalls 的函数名与参数 JSON）匹配未消费记录，匹配不到再按
 * 录制顺序取下一条；用例数多于录制数（录制耗尽）时抛运行期异常。</p>
 *
 * <p><b>流式边界</b>：{@link #chatStream} 在录制模式下把分片聚合成完整响应（首片 id、
 * deltaText 拼接、toolCalls 合并、finishReason）后落盘——聚合响应的 {@code usage} 为
 * null（分片通常不带用量），与真实流式计费口径可能有差异，评估回归应关注同步
 * {@code chat} 路径；回放模式把录制响应整体作为单分片投递。</p>
 *
 * <p><b>生命周期</b>：{@link Path} 工厂自建 writer 并在 {@link #close()} 时关闭；
 * {@link Writer} 工厂只 flush、不关闭（调用方自持）。委托客户端<b>默认不关闭</b>，
 * 仅在显式传入 {@code closeDelegate=true} 时随 {@link #close()} 关闭。</p>
 *
 * <p>线程安全：追加与回放消费均同步串行化，支持 {@code EvalRunner} 并发执行下共享本客户端。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class RecordingClient implements AiClient, AutoCloseable {

	private final AiClient delegate;
	private final Writer writer;
	private final boolean closeWriter;
	private final boolean closeDelegate;
	private final List<EvalTrace> traces;
	private final boolean[] consumed;
	private final Object lock = new Object();
	private int replayCalls;
	private long sequence;

	private RecordingClient(AiClient delegate, Writer writer, boolean closeWriter,
			boolean closeDelegate, List<EvalTrace> traces) {
		this.delegate = delegate;
		this.writer = writer;
		this.closeWriter = closeWriter;
		this.closeDelegate = closeDelegate;
		this.traces = traces;
		this.consumed = traces == null ? null : new boolean[traces.size()];
	}

	/**
	 * 录制模式：追加 JSONL 到文件（UTF-8，不存在则创建），默认不关闭委托客户端。
	 *
	 * @param delegate 真实对话客户端
	 * @param target   JSONL 文件
	 * @return 录制客户端
	 */
	public static RecordingClient record(AiClient delegate, Path target) {
		return record(delegate, target, false);
	}

	/**
	 * 录制模式：追加 JSONL 到文件。
	 *
	 * @param delegate      真实对话客户端
	 * @param target        JSONL 文件
	 * @param closeDelegate {@link #close()} 时是否一并关闭委托客户端
	 * @return 录制客户端
	 */
	public static RecordingClient record(AiClient delegate, Path target, boolean closeDelegate) {
		Assert.notNull(delegate, "delegate 不能为 null");
		Assert.notNull(target, "target 不能为 null");
		try {
			Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			return new RecordingClient(delegate, writer, true, closeDelegate, null);
		} catch (IOException e) {
			throw new EvalException("打开录制文件失败: " + target, e);
		}
	}

	/**
	 * 录制模式：写入调用方提供的 writer（只 flush 不关闭；不关闭委托客户端）。
	 *
	 * @param delegate 真实对话客户端
	 * @param target   输出 writer
	 * @return 录制客户端
	 */
	public static RecordingClient record(AiClient delegate, Writer target) {
		Assert.notNull(delegate, "delegate 不能为 null");
		Assert.notNull(target, "target 不能为 null");
		return new RecordingClient(delegate, target, false, false, null);
	}

	/**
	 * 回放模式：从 JSONL 文件加载录制记录，零网络。
	 *
	 * @param source JSONL 文件
	 * @return 回放客户端
	 */
	public static RecordingClient replay(Path source) {
		Assert.notNull(source, "source 不能为 null");
		try {
			return new RecordingClient(null, null, false, false,
				parseLines(Files.readAllLines(source, StandardCharsets.UTF_8), source.toString()));
		} catch (IOException e) {
			throw new EvalException("读取录制文件失败: " + source, e);
		}
	}

	/**
	 * 回放模式：从 reader 加载录制记录，零网络。
	 *
	 * @param source 录制文本
	 * @return 回放客户端
	 */
	public static RecordingClient replay(Reader source) {
		Assert.notNull(source, "source 不能为 null");
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(source)) {
			String line;
			while ((line = reader.readLine()) != null) {
				lines.add(line);
			}
		} catch (IOException e) {
			throw new EvalException("读取录制内容失败", e);
		}
		return new RecordingClient(null, null, false, false, parseLines(lines, "<reader>"));
	}

	private static List<EvalTrace> parseLines(List<String> lines, String sourceName) {
		List<EvalTrace> traces = new ArrayList<>();
		int lineNo = 0;
		for (String line : lines) {
			lineNo++;
			if (line == null || line.isBlank()) {
				continue;
			}
			try {
				traces.add(EvalTrace.parse(line));
			} catch (RuntimeException e) {
				throw new EvalException("录制文件 " + sourceName + " 第 " + lineNo
					+ " 行 JSONL 非法: " + e.getMessage(), e);
			}
		}
		return List.copyOf(traces);
	}

	@Override
	public String name() {
		return this.traces == null ? "recording(" + this.delegate.name() + ")" : "replay";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		Assert.notNull(request, "request 不能为 null");
		synchronized (this.lock) {
			if (this.traces == null) {
				ChatResponse response = this.delegate.chat(request);
				append(new EvalTrace("call-" + (++this.sequence), System.currentTimeMillis(),
					request, response));
				return response;
			}
			return nextTrace(request).response();
		}
	}

	@Override
	public void chatStream(ChatRequest request,
			java.util.function.Consumer<ChatStreamChunk> consumer) {
		Assert.notNull(request, "request 不能为 null");
		Assert.notNull(consumer, "consumer 不能为 null");
		synchronized (this.lock) {
			if (this.traces == null) {
				StringBuilder text = new StringBuilder();
				List<ToolCall> mergedCalls = new ArrayList<>();
				String[] id = {null};
				String[] finish = {null};
				this.delegate.chatStream(request, chunk -> {
					if (chunk.id() != null) {
						id[0] = chunk.id();
					}
					if (chunk.deltaText() != null) {
						text.append(chunk.deltaText());
					}
					if (chunk.toolCalls() != null) {
						mergedCalls.addAll(chunk.toolCalls());
					}
					if (chunk.finishReason() != null) {
						finish[0] = chunk.finishReason();
					}
					consumer.accept(chunk);
				});
				ChatMessage message = mergedCalls.isEmpty()
					? ChatMessage.assistant(text.toString())
					: ChatMessage.assistant(List.copyOf(mergedCalls));
				ChatResponse aggregated = ChatResponse.of(
					id[0] == null ? "stream-" + (++this.sequence) : id[0],
					request.model(), List.of(Choice.of(0, message, finish[0])), null, null);
				append(new EvalTrace("stream-" + this.sequence, System.currentTimeMillis(),
					request, aggregated));
				return;
			}
			ChatResponse response = nextTrace(request).response();
			Choice first = response.choices().isEmpty() ? null : response.choices().get(0);
			String finishReason = first == null ? null : first.finishReason();
			String textOut = first == null ? null : first.message().content();
			consumer.accept(ChatStreamChunk.of(response.id(), Role.ASSISTANT, textOut,
				first == null ? null : first.message().toolCalls(), finishReason));
		}
	}

	/**
	 * 关闭资源：Path 工厂自建的 writer 随本方法关闭（Writer 工厂只 flush）；
	 * 委托客户端仅在显式 {@code closeDelegate=true} 时关闭。
	 */
	@Override
	public void close() {
		synchronized (this.lock) {
			try {
				if (this.writer != null) {
					if (this.closeWriter) {
						this.writer.close();
					} else {
						this.writer.flush();
					}
				}
			} catch (IOException e) {
				throw new EvalException("关闭录制 writer 失败", e);
			}
			if (this.closeDelegate && this.delegate != null) {
				this.delegate.close();
			}
		}
	}

	private void append(EvalTrace trace) {
		try {
			this.writer.write(EvalTrace.toJsonLine(trace));
			this.writer.write(System.lineSeparator());
			this.writer.flush();
		} catch (IOException e) {
			throw new EvalException("录制落盘失败", e);
		}
	}

	private EvalTrace nextTrace(ChatRequest request) {
		for (int i = 0; i < this.traces.size(); i++) {
			if (!this.consumed[i] && sameRequest(request, this.traces.get(i).request())) {
				this.consumed[i] = true;
				this.replayCalls++;
				return this.traces.get(i);
			}
		}
		for (int i = 0; i < this.traces.size(); i++) {
			if (!this.consumed[i]) {
				this.consumed[i] = true;
				this.replayCalls++;
				return this.traces.get(i);
			}
		}
		throw new EvalException("回放录制耗尽：第 " + (this.replayCalls + 1)
			+ " 次调用，仅录制 " + this.traces.size() + " 条");
	}

	private static boolean sameRequest(ChatRequest a, ChatRequest b) {
		if (!a.model().equals(b.model()) || a.messages().size() != b.messages().size()) {
			return false;
		}
		for (int i = 0; i < a.messages().size(); i++) {
			if (!sameMessage(a.messages().get(i), b.messages().get(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean sameMessage(ChatMessage a, ChatMessage b) {
		if (a.role() != b.role()
			|| !equalsNullable(a.content(), b.content())
			|| !equalsNullable(a.toolCallId(), b.toolCallId())) {
			return false;
		}
		List<ToolCall> callsA = a.toolCalls();
		List<ToolCall> callsB = b.toolCalls();
		if (callsA == null || callsB == null) {
			return callsA == callsB;
		}
		if (callsA.size() != callsB.size()) {
			return false;
		}
		for (int i = 0; i < callsA.size(); i++) {
			ToolCall x = callsA.get(i);
			ToolCall y = callsB.get(i);
			if (!equalsNullable(x.id(), y.id())
				|| !equalsNullable(x.name(), y.name())
				|| !equalsNullable(x.argumentsJson(), y.argumentsJson())) {
				return false;
			}
		}
		return true;
	}

	private static boolean equalsNullable(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}
}
