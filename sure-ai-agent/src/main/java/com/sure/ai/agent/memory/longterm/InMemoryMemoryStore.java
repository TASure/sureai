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
package com.sure.ai.agent.memory.longterm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的内存向量记忆存储。
 *
 * <p>线程安全；{@link #search(float[], int)} 对所有带向量的条目做全量余弦相似度
 * 计算后取 Top-K（数据量大时应替换为外部向量库实现）；
 * {@link #searchByText(String, int)} 对正文做大小写不敏感的包含匹配，
 * 命中即按命中次数降序、再按创建时间升序取 Top-K。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class InMemoryMemoryStore implements VectorMemoryStore {

	/** id → 条目。 */
	private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();

	@Override
	public void put(MemoryEntry entry) {
		if (entry == null) {
			throw new IllegalArgumentException("entry must not be null");
		}
		this.entries.put(entry.id(), entry);
	}

	@Override
	public Optional<MemoryEntry> get(String id) {
		return Optional.ofNullable(id == null ? null : this.entries.get(id));
	}

	@Override
	public void delete(String id) {
		if (id != null) {
			this.entries.remove(id);
		}
	}

	@Override
	public List<MemoryEntry> all() {
		return new ArrayList<>(this.entries.values());
	}

	@Override
	public void clear() {
		this.entries.clear();
	}

	@Override
	public List<MemoryEntry> search(float[] queryVector, int k) {
		if (queryVector == null || queryVector.length == 0) {
			return List.of();
		}
		List<Scored> scored = new ArrayList<>();
		for (MemoryEntry e : this.entries.values()) {
			float[] vec = e.embedding();
			if (vec == null || vec.length != queryVector.length) {
				continue;
			}
			double sim = cosine(queryVector, vec);
			scored.add(new Scored(e, sim));
		}
		scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
		List<MemoryEntry> result = new ArrayList<>();
		for (int i = 0; i < Math.min(k, scored.size()); i++) {
			result.add(scored.get(i).entry);
		}
		return result;
	}

	@Override
	public List<MemoryEntry> searchByText(String query, int k) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		String lower = query.toLowerCase();
		List<Scored> scored = new ArrayList<>();
		for (MemoryEntry e : this.entries.values()) {
			String content = e.content() == null ? "" : e.content().toLowerCase();
			if (content.contains(lower)) {
				scored.add(new Scored(e, (double) countOccurrences(content, lower)));
			}
		}
		scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed()
			.thenComparingLong(s -> s.entry.createdAtEpochMs()));
		List<MemoryEntry> result = new ArrayList<>();
		for (int i = 0; i < Math.min(k, scored.size()); i++) {
			result.add(scored.get(i).entry);
		}
		return result;
	}

	/**
	 * 余弦相似度。
	 */
	private static double cosine(float[] a, float[] b) {
		double dot = 0.0;
		double na = 0.0;
		double nb = 0.0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		if (na == 0.0 || nb == 0.0) {
			return 0.0;
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb));
	}

	/**
	 * 子串出现次数（非重叠）。
	 */
	private static int countOccurrences(String text, String sub) {
		int count = 0;
		int idx = 0;
		while ((idx = text.indexOf(sub, idx)) >= 0) {
			count++;
			idx += sub.length();
		}
		return count;
	}

	/** 相似度中间结果。 */
	private record Scored(MemoryEntry entry, double score) {
	}
}
