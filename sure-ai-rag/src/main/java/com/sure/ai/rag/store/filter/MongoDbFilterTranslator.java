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
package com.sure.ai.rag.store.filter;

import java.util.List;

import com.sure.ai.rag.store.mongodb.Bson;

/**
 * MongoDB 查询方言翻译器：把可移植表达式翻译成 BSON 查询谓词文档（供 {@code $vectorSearch.filter} 使用）。
 *
 * <p>各叶子直接映射 MongoDB 查询操作符：</p>
 * <ul>
 *   <li>Eq：{@code {field: v}}；Ne：{@code {field: {$ne: v}}}；In：{@code {field: {$in:[...]}}}；</li>
 *   <li>范围：Gt/Gte/Lt/Lte → {@code {$gt|$gte|$lt|$lte}}；</li>
 *   <li>逻辑：And → {@code {$and:[...]}}、Or → {@code {$or:[...]}}、Not → {@code {$not:{...}}}。</li>
 * </ul>
 *
 * <p><b>能力边界（如实标注）</b>：覆盖密封表达式全部叶子与逻辑组合；值按 String/Number/Boolean
 * 映射为 BSON string/double/boolean。该谓词作为 Atlas/自管 7.0+ 向量索引的 {@code filter} 传入，
 * 不支持的服务端版本会在聚合阶段返回错误（由调用方感知）。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class MongoDbFilterTranslator implements FilterTranslator<Bson.Doc> {

	/** 构造翻译器。 */
	public MongoDbFilterTranslator() {
	}

	@Override
	public Bson.Doc translate(FilterExpression filter) {
		if (filter == null) {
			return null;
		}
		return expr(filter);
	}

	private Bson.Doc expr(FilterExpression e) {
		if (e instanceof And and) {
			Bson.Doc doc = new Bson.Doc();
			Bson.Doc arr = new Bson.Doc();
			int i = 0;
			for (FilterExpression c : and.conditions()) {
				arr.addDoc(String.valueOf(i++), expr(c));
			}
			return doc.addArray("$and", arr);
		}
		if (e instanceof Or or) {
			Bson.Doc doc = new Bson.Doc();
			Bson.Doc arr = new Bson.Doc();
			int i = 0;
			for (FilterExpression c : or.conditions()) {
				arr.addDoc(String.valueOf(i++), expr(c));
			}
			return doc.addArray("$or", arr);
		}
		if (e instanceof Not not) {
			return new Bson.Doc().addDoc("$not", expr(not.expression()));
		}
		if (e instanceof Eq eq) {
			Bson.Doc doc = new Bson.Doc();
			return writeValue(doc, eq.field(), eq.value());
		}
		if (e instanceof Ne ne) {
			Bson.Doc op = new Bson.Doc();
			writeValue(op, "$ne", ne.value());
			return new Bson.Doc().addDoc(ne.field(), op);
		}
		if (e instanceof In in) {
			Bson.Doc arr = new Bson.Doc();
			List<Object> values = in.values();
			for (int i = 0; i < values.size(); i++) {
				addValue(arr, String.valueOf(i), values.get(i));
			}
			return new Bson.Doc().addDoc(in.field(), new Bson.Doc().addArray("$in", arr));
		}
		if (e instanceof Gt gt) {
			return new Bson.Doc().addDoc(gt.field(), new Bson.Doc().addDouble("$gt", asDouble(gt.value())));
		}
		if (e instanceof Gte gte) {
			return new Bson.Doc().addDoc(gte.field(), new Bson.Doc().addDouble("$gte", asDouble(gte.value())));
		}
		if (e instanceof Lt lt) {
			return new Bson.Doc().addDoc(lt.field(), new Bson.Doc().addDouble("$lt", asDouble(lt.value())));
		}
		if (e instanceof Lte lte) {
			return new Bson.Doc().addDoc(lte.field(), new Bson.Doc().addDouble("$lte", asDouble(lte.value())));
		}
		throw new IllegalArgumentException("不支持的 MongoDB 条件: " + e.getClass().getName());
	}

	private static Bson.Doc writeValue(Bson.Doc doc, String field, Object value) {
		addValue(doc, field, value);
		return doc;
	}

	private static void addValue(Bson.Doc doc, String field, Object value) {
		if (value instanceof Number n) {
			doc.addDouble(field, n.doubleValue());
		} else if (value instanceof Boolean b) {
			doc.addBoolean(field, b);
		} else {
			doc.addString(field, String.valueOf(value));
		}
	}

	private static double asDouble(Object value) {
		return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
	}
}
