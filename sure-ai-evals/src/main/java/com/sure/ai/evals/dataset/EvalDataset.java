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

package com.sure.ai.evals.dataset;

import java.util.List;
import java.util.Map;

import com.sure.tool.lang.Assert;

/**
 * 评估数据集：一组有序 {@link EvalCase} 及其名称 / 元数据。
 *
 * <p>不可变。数据集由 {@code CsvDatasetLoader} / {@code JsonlDatasetLoader} 加载，
 * 或在代码中直接构造，交给 {@code EvalRunner} 批量执行。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalDataset {

	private final String name;
	private final List<EvalCase> cases;
	private final Map<String, Object> metadata;

	private EvalDataset(String name, List<EvalCase> cases, Map<String, Object> metadata) {
		this.name = name;
		this.cases = List.copyOf(cases);
		this.metadata = Map.copyOf(metadata);
	}

	/**
	 * 构造数据集。
	 *
	 * @param name  数据集名
	 * @param cases 用例列表
	 * @return 数据集
	 */
	public static EvalDataset of(String name, List<EvalCase> cases) {
		Assert.notNull(cases, "cases 不能为 null");
		return new EvalDataset(name, cases, Map.of());
	}

	/**
	 * 构造匿名数据集。
	 *
	 * @param cases 用例列表
	 * @return 数据集
	 */
	public static EvalDataset of(List<EvalCase> cases) {
		return of("dataset", cases);
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 数据集名。
	 *
	 * @return 名称
	 */
	public String name() {
		return this.name;
	}

	/**
	 * 用例列表。
	 *
	 * @return 不可变用例列表
	 */
	public List<EvalCase> cases() {
		return this.cases;
	}

	/**
	 * 元数据。
	 *
	 * @return 不可变元数据
	 */
	public Map<String, Object> metadata() {
		return this.metadata;
	}

	/**
	 * 用例数。
	 *
	 * @return 数量
	 */
	public int size() {
		return this.cases.size();
	}

	/**
	 * 按下标取用例。
	 *
	 * @param index 下标
	 * @return 用例
	 */
	public EvalCase get(int index) {
		return this.cases.get(index);
	}

	/**
	 * 按 ID 取用例，找不到返回 null。
	 *
	 * @param id 用例 ID
	 * @return 用例，或 null
	 */
	public EvalCase byId(String id) {
		for (EvalCase evalCase : this.cases) {
			if (evalCase.id().equals(id)) {
				return evalCase;
			}
		}
		return null;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String name = "dataset";
		private List<EvalCase> cases = List.of();
		private Map<String, Object> metadata = Map.of();

		private Builder() {
		}

		/**
		 * 设置数据集名。
		 *
		 * @param name 名称
		 * @return this
		 */
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * 设置用例列表。
		 *
		 * @param cases 用例
		 * @return this
		 */
		public Builder cases(List<EvalCase> cases) {
			Assert.notNull(cases, "cases 不能为 null");
			this.cases = cases;
			return this;
		}

		/**
		 * 设置元数据。
		 *
		 * @param metadata 元数据
		 * @return this
		 */
		public Builder metadata(Map<String, Object> metadata) {
			Assert.notNull(metadata, "metadata 不能为 null");
			this.metadata = metadata;
			return this;
		}

		/**
		 * 构建数据集。
		 *
		 * @return 数据集
		 */
		public EvalDataset build() {
			return new EvalDataset(this.name, this.cases, this.metadata);
		}
	}
}
