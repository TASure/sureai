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

package com.sure.ai.docsite;

/**
 * 文档站搜索脚本（纯 JS 客户端过滤，零第三方依赖、无 CDN）。
 *
 * @author sureai
 * @since 3.0.0
 */
final class Search {

	/** 内嵌 search.js：加载 search-index.json，按标题/摘要做大小写不敏感过滤。 */
	static final String JS = """
		(function () {
		  "use strict";
		  var box = document.getElementById("search-box");
		  var results = document.getElementById("search-results");
		  var index = [];
		  fetch("search-index.json")
		    .then(function (r) { return r.json(); })
		    .then(function (data) { index = data; })
		    .catch(function () { /* 索引缺失时搜索静默不可用 */ });

		  function render(hits) {
		    if (hits.length === 0) {
		      results.innerHTML = "<p>无匹配文档</p>";
		      return;
		    }
		    var html = "";
		    hits.forEach(function (h) {
		      html += "<article class=\\"card\\"><h3><a href=\\""
		        + h.file + "\\">" + h.title + "</a></h3><p>"
		        + h.text + "</p></article>";
		    });
		    results.innerHTML = html;
		  }

		  box.addEventListener("input", function () {
		    var q = box.value.trim().toLowerCase();
		    if (q.length === 0) {
		      results.innerHTML = "";
		      return;
		    }
		    var hits = index.filter(function (d) {
		      return d.title.toLowerCase().indexOf(q) >= 0
		        || d.text.toLowerCase().indexOf(q) >= 0;
		    });
		    render(hits.slice(0, 20));
		  });
		})();
		""";

	private Search() {
	}
}
