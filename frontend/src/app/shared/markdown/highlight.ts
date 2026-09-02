import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import java from 'highlight.js/lib/languages/java';
import json from 'highlight.js/lib/languages/json';
import markdown from 'highlight.js/lib/languages/markdown';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

/**
 * Languages registered by name, the same way the icons are named imports: highlight.js's
 * full bundle carries close to two hundred grammars, and an ad quotes a stack rather than
 * a compiler. `xml` is what highlight.js calls HTML, and `markdown` is what the review
 * screen shows an uploaded file in.
 *
 * <p>One registry for the whole app: registering the same language twice from two
 * components is harmless but means two places to look when a fence renders unhighlighted.
 */
for (const [name, language] of Object.entries({
  bash,
  java,
  json,
  markdown,
  sql,
  typescript,
  xml,
  yaml,
})) {
  hljs.registerLanguage(name, language);
}

export { hljs };

/**
 * Unknown language means the code is shown as it stands, and it still has to be escaped:
 * Angular's sanitizer would strip a stray `<script>` anyway, but it would also strip the
 * `<div>` an ad is quoting, which is content rather than an attack.
 */
export function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
