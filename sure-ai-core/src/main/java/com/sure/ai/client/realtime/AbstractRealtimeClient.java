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

package com.sure.ai.client.realtime;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;

/**
 * 实时对话客户端抽象基类。
 *
 * <p>持有可注入的 {@link RealtimeConnector}，实现通用的 connect/sendText/sendAudio/close
 * 流程；平台子类覆盖 {@link #buildUri()}（构造含模型与鉴权参数的 WebSocket URL）与
 * {@link #handleMessage(String)}（解析平台事件并回调 {@link RealtimeEventListener}）。</p>
 *
 * <p>2.4.0 起内置连接韧性（基于 JDK {@link WebSocket}，零第三方库）：</p>
 * <ul>
 *   <li><b>自动重连</b>：非用户主动关闭的掉线按 {@link RealtimeOptions} 指数退避重连，
 *       全过程通过 {@code onConnected/onDisconnected/onReconnecting/onReconnected/
 *       onReconnectFailed} 回调（均为 default 空实现，老实现者不受影响）；</li>
 *   <li><b>心跳保活</b>：周期性 JDK WebSocket ping，超时无任何入站帧即判定死亡并进入重连；</li>
 *   <li>重连/心跳任务经包内 {@link RealtimeTaskScheduler} 调度，测试可注入同步实现，
 *       核心逻辑零真实网络可测。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public abstract class AbstractRealtimeClient implements RealtimeClient {

	/** 连接配置。 */
	protected final AiConfig config;

	/** 事件监听器。 */
	protected final RealtimeEventListener eventListener;

	/** 连接选项（重连/心跳）。 */
	protected final RealtimeOptions options;

	/** WebSocket 连接器（可注入以 mock 测试）。 */
	private final RealtimeConnector connector;

	/** 当前 WebSocket，volatile 以保证可见性。 */
	private volatile WebSocket webSocket;

	/** 连接状态。 */
	private volatile boolean connected;

	/** 用户是否主动 close（true 时不再自动重连）。 */
	private volatile boolean userClosed;

	/** 上次入站活动时间戳（nanoTime），包内可见以便测试模拟空闲。 */
	volatile long lastActivityNanos;

	/** 已连续重连次数（成功后清零）。 */
	private final AtomicInteger reconnectAttempt = new AtomicInteger();

	/** 任务调度器（懒创建；测试可在 connect 前注入）。 */
	protected volatile RealtimeTaskScheduler taskScheduler;

	/** 当前心跳任务句柄。 */
	private volatile ScheduledFuture<?> heartbeatFuture;

	/**
	 * 构造客户端（使用默认连接选项）。
	 *
	 * @param config       连接配置
	 * @param connector    WebSocket 连接器
	 * @param eventListener 事件监听器
	 */
	protected AbstractRealtimeClient(AiConfig config, RealtimeConnector connector,
			RealtimeEventListener eventListener) {
		this(config, connector, eventListener, RealtimeOptions.defaults());
	}

	/**
	 * 构造客户端（自定义连接选项）。
	 *
	 * @param config       连接配置
	 * @param connector    WebSocket 连接器
	 * @param eventListener 事件监听器
	 * @param options      重连/心跳选项，null 视为默认
	 */
	protected AbstractRealtimeClient(AiConfig config, RealtimeConnector connector,
			RealtimeEventListener eventListener, RealtimeOptions options) {
		this.config = config;
		this.connector = connector;
		this.eventListener = eventListener;
		this.options = options == null ? RealtimeOptions.defaults() : options;
	}

	@Override
	public void connect() {
		this.userClosed = false;
		this.reconnectAttempt.set(0);
		openTransport();
	}

	/**
	 * 真正建立一次传输连接（公开 connect 与自动重连共用）。
	 *
	 * @return 新建的 WebSocket
	 */
	private WebSocket openTransport() {
		URI uri = buildUri();
		WebSocket ws = this.connector.connect(uri, new InternalListener());
		this.webSocket = ws;
		this.connected = true;
		this.lastActivityNanos = System.nanoTime();
		this.eventListener.onConnected();
		startHeartbeat();
		return ws;
	}

	/** 启动周期性心跳（已连接且未关闭时）。 */
	private void startHeartbeat() {
		stopHeartbeat();
		long interval = this.options.heartbeatIntervalMillis();
		if (interval > 0) {
			this.heartbeatFuture = taskScheduler().scheduleAtFixedRate(this::heartbeatTick, interval);
		}
	}

	/** 取消心跳。 */
	private void stopHeartbeat() {
		ScheduledFuture<?> f = this.heartbeatFuture;
		if (f != null) {
			f.cancel(false);
			this.heartbeatFuture = null;
		}
	}

	/**
	 * 心跳节拍：空闲超时则判定连接死亡并进入重连，否则发送一个 JDK WebSocket ping。
	 * 包内可见，测试可直接调用以零网络驱动超时判定。
	 */
	void heartbeatTick() {
		if (this.userClosed || !this.connected) {
			return;
		}
		long idleMs = (System.nanoTime() - this.lastActivityNanos) / 1_000_000L;
		if (idleMs > this.options.heartbeatTimeoutMillis()) {
			this.eventListener.onError("heartbeat timeout: no inbound frame for " + idleMs + "ms");
			onTransportDown(1006, "heartbeat timeout");
			return;
		}
		WebSocket ws = this.webSocket;
		if (ws != null) {
			try {
				ws.sendPing(ByteBuffer.allocate(0));
			} catch (RuntimeException ignore) {
				// ping 失败不影响连接判定，下一周期再试
			}
		}
	}

	/**
	 * 传输层掉线统一入口：置未连接、回调 onClose/onDisconnected，并按选项自动重连。
	 * 重复事件（onError 后又 onClose）由 connected 标志去重。
	 *
	 * @param code   WebSocket 关闭码
	 * @param reason 关闭原因
	 */
	private synchronized void onTransportDown(int code, String reason) {
		if (this.userClosed || !this.connected) {
			return;
		}
		this.connected = false;
		stopHeartbeat();
		this.eventListener.onClose();
		this.eventListener.onDisconnected(code, reason);
		if (!this.options.autoReconnect()) {
			return;
		}
		scheduleNextReconnect();
	}

	/** 依据退避策略安排下一次重连（达到上限则回调 onReconnectFailed）。 */
	private void scheduleNextReconnect() {
		int attempt = this.reconnectAttempt.incrementAndGet();
		if (attempt > this.options.maxReconnectAttempts()) {
			this.eventListener.onReconnectFailed(attempt);
			return;
		}
		long delay = this.options.backoffMillis(attempt);
		this.eventListener.onReconnecting(attempt, delay);
		taskScheduler().schedule(this::tryReconnect, delay);
	}

	/** 执行一次重连：成功则复位并回调；失败则安排下一次。 */
	private void tryReconnect() {
		if (this.userClosed) {
			return;
		}
		try {
			openTransport();
			int used = this.reconnectAttempt.get();
			this.reconnectAttempt.set(0);
			this.eventListener.onReconnected(used);
			onReconnectedSession();
		} catch (RuntimeException ex) {
			this.eventListener.onError("reconnect failed: " + ex.getMessage());
			scheduleNextReconnect();
		}
	}

	/**
	 * 重连成功后的会话级回调（默认空）。平台可覆盖以在新会话上重发握手后必需的初始化帧
	 * （如 Gemini Live 的 {@code setup}）。
	 */
	protected void onReconnectedSession() {
	}

	@Override
	public void sendText(String text) {
		requireConnected();
		this.webSocket.sendText(text, true);
	}

	@Override
	public void sendAudio(byte[] audio) {
		String b64 = Base64.getEncoder().encodeToString(audio);
		sendAudioBase64(b64);
	}

	/**
	 * 发送已 base64 编码的音频分片。默认直接发送原始 base64 文本，
	 * 平台子类可覆盖以封装为平台协议事件。
	 *
	 * @param base64Audio base64 编码音频
	 */
	protected void sendAudioBase64(String base64Audio) {
		sendText(base64Audio);
	}

	@Override
	public void close() {
		synchronized (this) {
			this.userClosed = true;
		}
		stopHeartbeat();
		RealtimeTaskScheduler s = this.taskScheduler;
		if (s != null) {
			s.shutdown();
		}
		WebSocket ws = this.webSocket;
		if (ws != null) {
			try {
				ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
			} catch (RuntimeException ex) {
				this.eventListener.onError("close failed: " + ex.getMessage());
			}
		}
		this.connected = false;
		this.eventListener.onClose();
		if (this.connector instanceof AutoCloseable ac) {
			try {
				ac.close();
			} catch (Exception ex) {
				this.eventListener.onError("connector close failed: " + ex.getMessage());
			}
		}
	}

	@Override
	public boolean isConnected() {
		return this.connected;
	}

	/**
	 * 构造 WebSocket 连接 URL（含模型/鉴权参数）。
	 *
	 * @return URI
	 */
	protected abstract URI buildUri();

	/**
	 * 解析收到的平台事件消息并回调监听器。
	 *
	 * @param message 原始消息文本
	 */
	protected abstract void handleMessage(String message);

	/** 取任务调度器（懒创建守护线程池；测试可预先注入同步实现）。 */
	protected RealtimeTaskScheduler taskScheduler() {
		RealtimeTaskScheduler s = this.taskScheduler;
		if (s == null) {
			synchronized (this) {
				s = this.taskScheduler;
				if (s == null) {
					ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
						Thread t = new Thread(r, "sureai-realtime-scheduler");
						t.setDaemon(true);
						return t;
					});
					s = new DefaultTaskScheduler(exec);
					this.taskScheduler = s;
				}
			}
		}
		return s;
	}

	/** 校验已连接。 */
	private void requireConnected() {
		if (!this.connected || this.webSocket == null) {
			throw new AiException("realtime client is not connected");
		}
	}

	/** WebSocket 消息监听器：把文本帧转发到 handleMessage，并维护活动时间戳。 */
	private final class InternalListener implements WebSocket.Listener {

		@Override
		public void onOpen(WebSocket socket) {
			socket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
			AbstractRealtimeClient.this.lastActivityNanos = System.nanoTime();
			try {
				handleMessage(data.toString());
			} catch (RuntimeException ex) {
				AbstractRealtimeClient.this.eventListener.onError("handle message failed: " + ex.getMessage());
			}
			socket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
			AbstractRealtimeClient.this.lastActivityNanos = System.nanoTime();
			socket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			AbstractRealtimeClient.this.eventListener.onError(
				error.getMessage() == null ? error.toString() : error.getMessage());
			onTransportDown(1006, error.toString());
		}

		@Override
		public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
			onTransportDown(statusCode, reason);
			return null;
		}
	}

	/** 基于 JDK 单线程守护池的默认任务调度器。 */
	private static final class DefaultTaskScheduler implements RealtimeTaskScheduler {

		private final ScheduledExecutorService exec;

		DefaultTaskScheduler(ScheduledExecutorService exec) {
			this.exec = exec;
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
			return this.exec.schedule(task, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long intervalMillis) {
			return this.exec.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
		}

		@Override
		public void shutdown() {
			this.exec.shutdownNow();
		}
	}
}
