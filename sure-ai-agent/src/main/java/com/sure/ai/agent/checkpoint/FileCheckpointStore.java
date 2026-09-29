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

package com.sure.ai.agent.checkpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.sure.ai.exception.AiException;
import com.sure.tool.lang.Assert;

/**
 * 基于本地目录的文件检查点存储：每个会话一个 JSON 文件 {@code {dir}/{sessionId}.json}。
 *
 * <p>目录不存在时自动创建。为防止路径穿越，{@code sessionId} 会被清洗：
 * 仅保留 {@code [A-Za-z0-9_-]}，其余字符替换为 {@code _}；解析后的规范化路径
 * 必须仍位于根目录之下，否则抛出 {@link AiException}。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class FileCheckpointStore implements CheckpointStore {

	private final Path dir;

	/**
	 * 用目录字符串构造。
	 *
	 * @param dir 存储目录
	 */
	public FileCheckpointStore(String dir) {
		this(Paths.get(dir));
	}

	/**
	 * 用目录路径构造。
	 *
	 * @param dir 存储目录
	 */
	public FileCheckpointStore(Path dir) {
		Assert.notNull(dir, "dir must not be null");
		this.dir = dir.toAbsolutePath().normalize();
		try {
			Files.createDirectories(this.dir);
		} catch (IOException e) {
			throw new AiException("无法创建检查点目录: " + this.dir, e);
		}
	}

	@Override
	public void save(AgentCheckpoint checkpoint) {
		Assert.notNull(checkpoint, "checkpoint must not be null");
		Path file = resolve(checkpoint.sessionId());
		try {
			Files.writeString(file, CheckpointSerializer.toJson(checkpoint), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
		} catch (IOException e) {
			throw new UncheckedIOException("写入检查点失败: " + file, e);
		}
	}

	@Override
	public Optional<AgentCheckpoint> load(String sessionId) {
		Assert.notBlank(sessionId, "sessionId must not be blank");
		Path file = resolve(sessionId);
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			return Optional.of(CheckpointSerializer.fromJson(json));
		} catch (IOException e) {
			throw new UncheckedIOException("读取检查点失败: " + file, e);
		}
	}

	@Override
	public void delete(String sessionId) {
		Assert.notBlank(sessionId, "sessionId must not be blank");
		try {
			Files.deleteIfExists(resolve(sessionId));
		} catch (IOException e) {
			throw new UncheckedIOException("删除检查点失败: " + sessionId, e);
		}
	}

	@Override
	public List<String> listSessions() {
		try (Stream<Path> s = Files.list(this.dir)) {
			List<String> names = new ArrayList<>();
			s.forEach(p -> {
				Path fileName = p.getFileName();
				if (fileName != null && fileName.toString().endsWith(".json")) {
					String n = fileName.toString();
					names.add(n.substring(0, n.length() - ".json".length()));
				}
			});
			Collections.sort(names);
			return names;
		} catch (IOException e) {
			throw new UncheckedIOException("列出检查点失败", e);
		}
	}

	/** 把 sessionId 清洗为安全文件名并解析为根目录下的路径。 */
	private Path resolve(String sessionId) {
		Assert.notBlank(sessionId, "sessionId must not be blank");
		String safe = sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
		if (safe.isEmpty()) {
			safe = "_";
		}
		Path file = this.dir.resolve(safe + ".json").normalize();
		if (!file.startsWith(this.dir)) {
			throw new AiException("非法 sessionId（路径穿越）: " + sessionId);
		}
		return file;
	}
}
