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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.sure.ai.agent.approval.ApprovalRequest;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link com.sure.ai.agent.checkpoint} 包边界补充测试。
 *
 * <p>覆盖文件存储字符串构造、会话名清洗、序列化异常根节点、空历史/空元数据
 * 紧凑构造与门面 save/load 委托。</p>
 */
public class CheckpointEdgeTest {

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void testFileStoreStringDirRoundTrip() throws Exception {
		Path dir = this.temp.newFolder().toPath();
		FileCheckpointStore store = new FileCheckpointStore(dir.toString());
		AgentCheckpoint cp = new AgentCheckpoint("sess-1",
				List.of(ChatMessage.user("hi")), 1, "ans", 123L, Json.object());
		store.save(cp);
		Optional<AgentCheckpoint> loaded = store.load("sess-1");
		assertTrue(loaded.isPresent());
		assertEquals(1, loaded.get().iteration());
		assertEquals("ans", loaded.get().finalAnswer());
		store.delete("sess-1");
		assertFalse(store.load("sess-1").isPresent());
	}

	@Test
	public void testFileStoreSanitizesWeirdSessionId() throws Exception {
		Path dir = this.temp.newFolder().toPath();
		FileCheckpointStore store = new FileCheckpointStore(dir);
		// 全部非法字符 → 清洗为 "_"
		AgentCheckpoint cp = new AgentCheckpoint("///",
				List.of(ChatMessage.user("x")), 0, null, 1L, Json.object());
		store.save(cp);
		Optional<AgentCheckpoint> loaded = store.load("///");
		assertTrue(loaded.isPresent());
		List<String> sessions = store.listSessions();
		assertTrue(sessions.contains("___"));
	}

	@Test
	public void testSerializerRejectsNonObjectRoot() {
		assertThrows(AiException.class, () -> CheckpointSerializer.fromJson("[1,2,3]"));
	}

	@Test
	public void testCheckpointNullHistoryAndMetadata() {
		AgentCheckpoint cp = new AgentCheckpoint("s", null, 0, null, 1L, null);
		assertEquals(List.of(), cp.history());
		assertTrue(cp.metadata().keySet().iterator().hasNext() == false);
	}

	@Test
	public void testCheckpointerSaveLoadDelegation() {		InMemoryCheckpointStore store = new InMemoryCheckpointStore();
		AgentCheckpoint cp = new AgentCheckpoint("facade",
				List.of(ChatMessage.user("via facade")), 0, null, 1L, Json.object());
		AgentCheckpointer.save(store, cp);
		Optional<AgentCheckpoint> loaded = AgentCheckpointer.load(store, "facade");
		assertTrue(loaded.isPresent());
		assertEquals(1, store.listSessions().size());
		store.delete("facade");
		assertFalse(store.load("facade").isPresent());
	}

	@Test
	public void testCheckpointRejectsBlankSessionId() {
		assertThrows(IllegalArgumentException.class,
				() -> new AgentCheckpoint(" ", List.of(), 0, null, 1L, null));
	}

	@Test
	public void testApprovalRequestDirectCtorDefaultsId() {
		ApprovalRequest r = new ApprovalRequest(null, "a", "pay",
				Json.object(), "d", true, 1L);
		assertNotNull(r.requestId());
	}

	@Test
	public void testFileStoreConstructorFailsWhenDirIsFile() throws Exception {
		// 传入一个已存在为普通文件的路径 → createDirectories 抛 IOException
		Path file = this.temp.newFile().toPath();
		assertThrows(com.sure.ai.exception.AiException.class,
				() -> new FileCheckpointStore(file));
	}

	@Test
	public void testFileStoreWriteReadIOExceptionIsCaught() throws Exception {
		Path dir = this.temp.newFolder().toPath();
		FileCheckpointStore store = new FileCheckpointStore(dir);
		// 在落盘路径上制造同名目录，使 writeString / readString 抛 IOException 被收敛
		Files.createDirectories(dir.resolve("blocked.json"));
		AgentCheckpoint cp = new AgentCheckpoint("blocked",
				List.of(ChatMessage.user("x")), 0, null, 1L, Json.object());
		// 同名目录导致写入抛 UncheckedIOException（在 save 内包装后向上抛）
		assertThrows(java.io.UncheckedIOException.class, () -> store.save(cp));
		// 读取目录同样抛 UncheckedIOException
		assertThrows(java.io.UncheckedIOException.class, () -> store.load("blocked"));
	}

	@Test
	public void testFileStoreDeleteNonEmptyDirFails() throws Exception {
		Path dir = this.temp.newFolder().toPath();
		FileCheckpointStore store = new FileCheckpointStore(dir);
		// blocked.json 是一个含文件的目录 → deleteIfExists 抛 DirectoryNotEmptyException
		Path sub = dir.resolve("blocked.json");
		Files.createDirectories(sub);
		Files.createFile(sub.resolve("inner.json"));
		assertThrows(java.io.UncheckedIOException.class, () -> store.delete("blocked"));
	}
}
