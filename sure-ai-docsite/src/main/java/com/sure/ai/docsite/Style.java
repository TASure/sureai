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
 * 文档站内嵌静态资源（样式与搜索脚本，纯 JDK 零第三方依赖、无外链）。
 *
 * @author sureai
 * @since 3.0.0
 */
final class Style {

	/** 站点样式表（轻量、无外链字体）。 */
	static final String CSS = """
		:root { --accent:#2563eb; --bg:#f8fafc; --card:#ffffff; --text:#0f172a; }
		* { box-sizing:border-box; }
		body { margin:0; font-family:system-ui,-apple-system,"Segoe UI",sans-serif;
			background:var(--bg); color:var(--text); line-height:1.65; }
		.site-header { background:var(--card); border-bottom:1px solid #e2e8f0;
			padding:12px 24px; }
		.brand { font-weight:700; color:var(--accent); text-decoration:none; font-size:1.1rem; }
		.layout { display:flex; max-width:1200px; margin:0 auto; gap:24px; padding:24px; }
		.sidebar { width:240px; flex-shrink:0; display:flex; flex-direction:column;
			gap:2px; max-height:calc(100vh - 80px); overflow:auto; position:sticky; top:24px; }
		.nav-link { padding:4px 10px; border-radius:6px; color:var(--text);
			text-decoration:none; font-size:0.9rem; }
		.nav-link:hover { background:#eef2ff; color:var(--accent); }
		.content { flex:1; min-width:0; background:var(--card); padding:24px 32px;
			border-radius:12px; box-shadow:0 1px 3px rgba(0,0,0,.06); overflow-wrap:break-word; }
		.content pre { background:#0f172a; color:#e2e8f0; padding:14px; border-radius:8px;
			overflow:auto; font-size:0.86rem; }
		.content code { background:#f1f5f9; padding:2px 5px; border-radius:4px;
			font-size:0.88em; }
		.content pre code { background:none; padding:0; }
		.content table { border-collapse:collapse; width:100%; margin:12px 0; }
		.content th, .content td { border:1px solid #e2e8f0; padding:8px 10px;
			text-align:left; font-size:0.92rem; }
		.content th { background:#f8fafc; }
		.content blockquote { border-left:4px solid var(--accent); margin:12px 0;
			padding:4px 16px; color:#475569; background:#f8fafc; }
		.content img { max-width:100%; }
		.page-title { margin-top:0; border-bottom:2px solid var(--accent);
			padding-bottom:8px; }
		.page-footer { margin-top:28px; padding-top:12px; border-top:1px solid #e2e8f0; }
		.home { max-width:1200px; margin:0 auto; padding:24px; }
		.hero { background:var(--card); border-radius:12px; padding:28px 32px;
			box-shadow:0 1px 3px rgba(0,0,0,.06); }
		#search-box { width:100%; max-width:560px; padding:10px 14px; font-size:1rem;
			border:1px solid #cbd5e1; border-radius:8px; margin-top:12px; }
		.search-results { margin-top:8px; }
		.category { margin-top:24px; }
		.category h2 { border-bottom:2px solid var(--accent); padding-bottom:6px; }
		.card { background:var(--card); border-radius:10px; padding:14px 18px; margin:10px 0;
			box-shadow:0 1px 3px rgba(0,0,0,.06); }
		.card h3 { margin:0 0 6px; }
		.card a { color:var(--accent); text-decoration:none; }
		.card p { margin:0; color:#475569; font-size:0.92rem; }
		.site-footer { text-align:center; color:#94a3b8; margin-top:28px; font-size:0.85rem; }
		@media (max-width:800px) { .layout { flex-direction:column; } .sidebar { width:auto;
			max-height:none; position:static; } }
		""";

	private Style() {
	}
}
