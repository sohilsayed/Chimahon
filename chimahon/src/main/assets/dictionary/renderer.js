(function () {
  'use strict';

  const DEBUG = false;
  const DEBUG_VERBOSE_NODES = false;
  const SCOPED_STYLE_CACHE_LIMIT = 24;
  const scopedStyleCache = new Map();

  // ── Recursive lookup state ────────────────────────────────────────────────
  const HOSHI_SCHEME = 'hoshi:';
  const MAX_SCAN_CHARS = 24;   // chars to extract forward from tap point

  let _lastSelection = '';
  let _selectedDictionaries = {}; // entryIndex -> dictName
  let _wordAudioEnabled = true;
  let _listenersInstalled = false;
  const HAS_NATIVE_SCOPE = (() => {
    try {
      if (!window.CSS || !CSS.supports) return false;
      return CSS.supports('selector(:scope)') && (() => {
        try {
          const sheet = new CSSStyleSheet();
          sheet.replaceSync('@scope (.test) { .a { color: red; } }');
          return sheet.cssRules.length > 0;
        } catch (e) {
          return false;
        }
      })();
    } catch (e) {
      return false;
    }
  })();

  const HAS_CONSTRUCTED_SHEETS = (() => {
    try {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync('body { color: red; }');
      return sheet.cssRules.length > 0;
    } catch (e) {
      return false;
    }
  })();

  function toDebugText(value) {
    if (typeof value === 'string') return value;
    if (value === null) return 'null';
    if (typeof value === 'undefined') return 'undefined';
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    try {
      return JSON.stringify(value);
    } catch (e) {
      return String(value);
    }
  }

  function debugLog(...args) {
    if (!DEBUG) return;
    console.log('[DictionaryRenderJS][debug]', args.map(toDebugText).join(' '));
  }

  function debugWarn(...args) {
    if (!DEBUG) return;
    console.warn('[DictionaryRenderJS][debug]', args.map(toDebugText).join(' '));
  }

  function decodeBase64Utf8(base64) {
    const binary = atob(base64);
    const len = binary.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
    return new TextDecoder('utf-8').decode(bytes);
  }

  function sanitizeDictionaryStyles(cssText) {
    if (!cssText || typeof cssText !== 'string') return '';
    return cssText;
  }

  function splitSelectorList(selectorText) {
    const out = [];
    let current = '';
    let parenDepth = 0;
    let bracketDepth = 0;
    let quote = null;

    for (let i = 0; i < selectorText.length; i++) {
      const ch = selectorText[i];
      const prev = i > 0 ? selectorText[i - 1] : '';

      if (quote) {
        current += ch;
        if (ch === quote && prev !== '\\') quote = null;
        continue;
      }

      if (ch === '"' || ch === "'") {
        quote = ch;
        current += ch;
        continue;
      }

      if (ch === '(') parenDepth++;
      if (ch === ')') parenDepth = Math.max(0, parenDepth - 1);
      if (ch === '[') bracketDepth++;
      if (ch === ']') bracketDepth = Math.max(0, bracketDepth - 1);

      if (ch === ',' && parenDepth === 0 && bracketDepth === 0) {
        if (current.trim()) out.push(current.trim());
        current = '';
        continue;
      }

      current += ch;
    }

    if (current.trim()) out.push(current.trim());
    return out;
  }

  function prefixSelectorList(selectorText, scopeSelector) {
    return splitSelectorList(selectorText)
      .map((sel) => {
        const trimmed = sel.trim();

        // Dictionary CSS often defines custom properties at :root/body/html.
        // Prefixing as "[scope] :root" is invalid, so inject scope right after
        // the root/html/body head selector.
        const rootHeadMatch = trimmed.match(/^((?:\:root|html|body)(?:[\w\-\[\]="'().:#]*)?)([\s\S]*)$/i);
        if (rootHeadMatch) {
          const head = rootHeadMatch[1];
          const tail = rootHeadMatch[2] || '';

          if (!tail.trim() && /^(?:\:root|html|body)$/i.test(head)) {
            return scopeSelector;
          }

          return `${head} ${scopeSelector}${tail}`;
        }

        return `${scopeSelector} ${trimmed}`;
      })
      .join(', ');
  }

  function getCachedScopedStyle(dictName, css, scopeSelector) {
    const cacheKey = `${dictName}\u0000${css}\u0000${HAS_NATIVE_SCOPE}`;
    if (scopedStyleCache.has(cacheKey)) {
      return {value: scopedStyleCache.get(cacheKey), cacheHit: true};
    }

    const value = buildDictionaryScopedStyle(css, scopeSelector);
    scopedStyleCache.set(cacheKey, value);
    if (scopedStyleCache.size > SCOPED_STYLE_CACHE_LIMIT) {
      const oldestKey = scopedStyleCache.keys().next().value;
      if (oldestKey) scopedStyleCache.delete(oldestKey);
    }
    return {value, cacheHit: false};
  }

  function findMatchingBrace(text, openBraceIndex) {
    let depth = 0;
    let quote = null;

    for (let i = openBraceIndex; i < text.length; i++) {
      const ch = text[i];
      const prev = i > 0 ? text[i - 1] : '';

      if (quote) {
        if (ch === quote && prev !== '\\') quote = null;
        continue;
      }

      if (ch === '"' || ch === "'") {
        quote = ch;
        continue;
      }

      if (ch === '{') depth++;
      if (ch === '}') {
        depth--;
        if (depth === 0) return i;
      }
    }

    return -1;
  }

  function scopeCssBlocks(cssText, scopeSelector) {
    let i = 0;
    let out = '';

    while (i < cssText.length) {
      while (i < cssText.length && /\s/.test(cssText[i])) i++;
      if (i >= cssText.length) break;

      if (cssText.startsWith('/*', i)) {
        const end = cssText.indexOf('*/', i + 2);
        if (end === -1) break;
        i = end + 2;
        continue;
      }

      let preludeStart = i;
      while (i < cssText.length && cssText[i] !== '{' && cssText[i] !== ';') i++;
      if (i >= cssText.length) break;

      const stopChar = cssText[i];
      const prelude = cssText.slice(preludeStart, i).trim();
      if (!prelude) {
        i++;
        continue;
      }

      if (stopChar === ';') {
        out += `${prelude};\n`;
        i++;
        continue;
      }

      const openBraceIndex = i;
      const closeBraceIndex = findMatchingBrace(cssText, openBraceIndex);
      if (closeBraceIndex === -1) {
        debugWarn('scopeCssBlocks.unmatchedBrace', {scopeSelector, prelude});
        break;
      }

      const body = cssText.slice(openBraceIndex + 1, closeBraceIndex);
      if (prelude.startsWith('@')) {
        if (/^@(media|supports|layer|document)\b/i.test(prelude)) {
          const scopedInner = scopeCssBlocks(body, scopeSelector);
          out += `${prelude} {\n${scopedInner}\n}\n`;
        } else {
          out += `${prelude} {\n${body}\n}\n`;
        }
      } else {
        const scopedSelectors = prefixSelectorList(prelude, scopeSelector);
        out += `${scopedSelectors} {\n${body}\n}\n`;
      }

      i = closeBraceIndex + 1;
    }

    return out.trim();
  }

  function scopeCssWithCSSOM(cssText, scopeSelector) {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(cssText);
    const parts = [];
    processRulesFromCSSOM(sheet.cssRules, scopeSelector, parts);
    return parts.join('\n');
  }

  function processRulesFromCSSOM(rules, scope, out) {
    for (let i = 0; i < rules.length; i++) {
      const rule = rules[i];
      const type = rule.type;

      if (type === CSSRule.STYLE_RULE) {
        const prefixed = rule.selectorText
          .split(',')
          .map(s => prefixSelectorForCSSOM(s.trim(), scope))
          .join(', ');
        out.push(`${prefixed} { ${rule.style.cssText} }`);
      } else if (type === CSSRule.MEDIA_RULE) {
        const inner = [];
        processRulesFromCSSOM(rule.cssRules, scope, inner);
        out.push(`@media ${rule.media.mediaText} {\n${inner.join('\n')}\n}`);
      } else if (type === CSSRule.SUPPORTS_RULE) {
        const inner = [];
        processRulesFromCSSOM(rule.cssRules, scope, inner);
        const condition = rule.conditionText || '';
        out.push(`@supports ${condition} {\n${inner.join('\n')}\n}`);
      } else {
        out.push(rule.cssText);
      }
    }
  }

  function prefixSelectorForCSSOM(sel, scope) {
    // :root / html / body alone → scope selector
    if (/^(:root|html|body)$/i.test(sel)) {
      return scope;
    }

    // :root[data-theme="dark"] .foo → :root[data-theme="dark"] [scope] .foo
    const rootWithAttr = sel.match(/^(:root|html|body)(\[[^\]]+\])([\s\S]*)$/i);
    if (rootWithAttr) {
      const head = rootWithAttr[1];
      const attr = rootWithAttr[2];
      const tail = rootWithAttr[3] || '';
      if (!tail.trim()) {
        return `${head}${attr} ${scope}`;
      }
      return `${head}${attr} ${scope}${tail}`;
    }

    // :root .foo → [scope] .foo
    const rootDesc = sel.match(/^(:root|html|body)\s+([\s\S]+)$/i);
    if (rootDesc) {
      return `${scope} ${rootDesc[2]}`;
    }

    // Regular selector → [scope] selector
    return `${scope} ${sel}`;
  }

  function buildDictionaryScopedStyle(css, scopeSelector) {
    const trimmed = css.trim();
    if (!trimmed) return '';

    // Declarations-only chunks can be wrapped directly.
    if (!trimmed.includes('{')) {
      const wrapped = `${scopeSelector} {\n${trimmed}\n}`;
      debugLog('buildDictionaryScopedStyle.declarationsOnly', {
        scopeSelector,
        inputLength: trimmed.length,
        outputLength: wrapped.length,
      });
      return wrapped;
    }

    // Three-tier scoping: native @scope > CSSOM > string parser
    if (HAS_NATIVE_SCOPE) {
      const scoped = `@scope (${scopeSelector}) {\n${trimmed}\n}`;
      debugLog('buildDictionaryScopedStyle.nativeScope', {
        scopeSelector,
        inputLength: trimmed.length,
        outputLength: scoped.length,
      });
      return scoped;
    }

    if (HAS_CONSTRUCTED_SHEETS) {
      try {
        const scoped = scopeCssWithCSSOM(trimmed, scopeSelector);
        if (scoped) {
          debugLog('buildDictionaryScopedStyle.cssom', {
            scopeSelector,
            inputLength: trimmed.length,
            outputLength: scoped.length,
          });
          return scoped;
        }
      } catch (e) {
        debugWarn('buildDictionaryScopedStyle.cssomFailed', {
          scopeSelector,
          error: e.message,
        });
      }
    }

    // Fallback: string-based CSS parser
    const scoped = scopeCssBlocks(trimmed, scopeSelector);
    if (scoped) {
      debugLog('buildDictionaryScopedStyle.fallback', {
        scopeSelector,
        inputLength: trimmed.length,
        outputLength: scoped.length,
      });
      return scoped;
    }

    const lastResort = `${scopeSelector} {\n${trimmed}\n}`;
    debugWarn('buildDictionaryScopedStyle.lastResort', {
      scopeSelector,
      inputLength: trimmed.length,
      outputLength: lastResort.length,
    });
    return lastResort;
  }

  function buildScopedDictionaryCss(stylesPayload) {
    const parts = [];
    debugLog('buildScopedDictionaryCss.start', {
      styleEntries: Array.isArray(stylesPayload) ? stylesPayload.length : 0
    });

    for (const styleEntry of stylesPayload) {
      if (typeof styleEntry === 'string') {
        const legacyCss = sanitizeDictionaryStyles(styleEntry);
        if (legacyCss) {
          parts.push(legacyCss);
          debugLog('buildScopedDictionaryCss.legacy', {
            cssLength: legacyCss.length,
            hasBackground: /background/i.test(legacyCss)
          });
        }
        continue;
      }
      if (!styleEntry || typeof styleEntry !== 'object') continue;

      const dictName = typeof styleEntry.dictName === 'string' ? styleEntry.dictName : '';
      const rawCss = typeof styleEntry.styles === 'string' ? styleEntry.styles : '';
      const css = sanitizeDictionaryStyles(rawCss);
      if (!dictName || !css) continue;

      const escapedName = dictName.replace(/"/g, '\\"');
      const selector = `[data-dictionary="${escapedName}"]`;
      const {value: scoped, cacheHit} = getCachedScopedStyle(dictName, css, selector);
      parts.push(scoped);
      debugLog('buildScopedDictionaryCss.dictionary', {
        dictName,
        cssLength: css.length,
        scopedLength: scoped.length,
        cacheHit,
        hasBackground: /background/i.test(css)
      });
    }
    const method = HAS_NATIVE_SCOPE ? 'native-scope' : (HAS_CONSTRUCTED_SHEETS ? 'cssom' : 'string-parser');
    const output = parts.join('\n\n');
    debugLog('buildScopedDictionaryCss.done', {outputLength: output.length, method});
    return output;
  }

  function trimFloat(value) {
    if (!Number.isFinite(value)) return '0';
    const rounded = Math.round(value * 10000) / 10000;
    return Number.isInteger(rounded) ? String(rounded) : String(rounded);
  }

  function sanitizeHref(href) {
    if (!href) return null;
    const value = String(href).trim();
    if (/^javascript:/i.test(value)) return null;
    return value;
  }

  function applyDataAttributes(node, data) {
    if (!data || typeof data !== 'object') return;
    for (const key of Object.keys(data)) {
      const value = data[key];
      if (value === null || typeof value === 'undefined') continue;
      if (key === 'class') {
        const classText = String(value);
        classText.split(' ').map((s) => s.trim()).filter(Boolean).forEach((klass) => node.classList.add(klass));
        // Keep legacy class selectors and data-sc-class selectors both working.
        node.setAttribute('data-sc-class', classText);
        if (DEBUG_VERBOSE_NODES) {
          debugLog('applyDataAttributes.class', {
            tag: node.tagName,
            className: node.className,
            source: classText
          });
        }
        continue;
      }
      const attrName = `data-sc-${key}`;
      // Keep empty-string values to preserve attribute-presence selectors.
      node.setAttribute(attrName, String(value));

      if (key === 'content' || key === 'headword' || key === 'example' || key === 'meaning') {
        debugLog('applyDataAttributes.attr', {
          tag: node.tagName,
          attrName,
          value: String(value)
        });
      }
    }
  }

  function applyStyle(node, style) {
    if (!style || typeof style !== 'object') return;
    const map = {
      fontStyle: 'fontStyle',
      fontWeight: 'fontWeight',
      fontSize: 'fontSize',
      color: 'color',
      background: 'background',
      backgroundColor: 'backgroundColor',
      textDecorationStyle: 'textDecorationStyle',
      textDecorationColor: 'textDecorationColor',
      borderColor: 'borderColor',
      borderStyle: 'borderStyle',
      borderRadius: 'borderRadius',
      borderWidth: 'borderWidth',
      clipPath: 'clipPath',
      verticalAlign: 'verticalAlign',
      textAlign: 'textAlign',
      textEmphasis: 'textEmphasis',
      textShadow: 'textShadow',
      margin: 'margin',
      padding: 'padding',
      paddingTop: 'paddingTop',
      paddingLeft: 'paddingLeft',
      paddingRight: 'paddingRight',
      paddingBottom: 'paddingBottom',
      whiteSpace: 'whiteSpace',
      wordBreak: 'wordBreak',
      cursor: 'cursor',
      listStyleType: 'listStyleType'
    };

    for (const [sourceKey, cssKey] of Object.entries(map)) {
      const value = style[sourceKey];
      if (typeof value === 'string' && value.length > 0) {
        node.style[cssKey] = value;
      }
    }

    const emSpacing = ['marginTop', 'marginLeft', 'marginRight', 'marginBottom'];
    for (const key of emSpacing) {
      const value = style[key];
      if (typeof value === 'number') {
        node.style[key] = `${value}em`;
      } else if (typeof value === 'string' && value.length > 0) {
        node.style[key] = value;
      }
    }

    const decorationLine = style.textDecorationLine;
    if (Array.isArray(decorationLine)) {
      node.style.textDecoration = decorationLine.join(' ');
    } else if (typeof decorationLine === 'string' && decorationLine.length > 0) {
      node.style.textDecoration = decorationLine;
    }
    if (typeof style.textDecoration === 'string' && style.textDecoration.length > 0) {
      node.style.textDecoration = style.textDecoration;
    }
  }

  function parseMaybeJson(text) {
    const trimmed = text.trim();
    if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return null;
    try {
      return JSON.parse(trimmed);
    } catch (e) {
      return null;
    }
  }

  function normalizePlainText(text) {
    return String(text)
      .replace(/\r\n?/g, '\n')
      .replace(/\\n/g, '\n');
  }

  function appendTextWithLineBreaks(parent, text) {
    const normalized = normalizePlainText(text);
    const parts = normalized.split('\n');
    for (let i = 0; i < parts.length; i += 1) {
      if (i > 0) {
        parent.appendChild(document.createElement('br'));
      }
      if (parts[i].length > 0) {
        parent.appendChild(document.createTextNode(parts[i]));
      }
    }
  }

  // ── Word extraction ───────────────────────────────────────────────────────

  function isCJK(ch) {
    const cp = ch.codePointAt(0);
    // CJK Unified, Katakana, Hiragana, Katakana ext, CJK compat, fullwidth
    return (cp >= 0x3000 && cp <= 0x9FFF) ||
           (cp >= 0xF900 && cp <= 0xFAFF) ||
           (cp >= 0xFF00 && cp <= 0xFFEF);
  }

  function isWordChar(ch) {
    return isCJK(ch) || /[\w\u00C0-\u024F\u0600-\u06FF]/.test(ch);
  }

  const scanDelimiters = '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r';
  function isScanBoundary(ch) {
    return /^[\s\u3000]$/.test(ch) || scanDelimiters.indexOf(ch) !== -1;
  }

  function isFurigana(node) {
    const el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    return !!(el && el.closest('rt, rp'));
  }

  /**
   * Returns up to MAX_SCAN_CHARS of text starting at the tapped position.
   * For CJK the full forward slice is useful (backend handles deinflection).
   * For space-separated scripts we expand left+right to word boundaries.
   */
  function extractTextAtPoint(x, y) {
    let range = null;
    if (document.caretRangeFromPoint) {
      range = document.caretRangeFromPoint(x, y);
    } else if (document.caretPositionFromPoint) {
      const pos = document.caretPositionFromPoint(x, y);
      if (pos) {
        range = document.createRange();
        range.setStart(pos.offsetNode, pos.offset);
      }
    }
    if (!range) return null;

    const node = range.startContainer;
    if (!node || node.nodeType !== Node.TEXT_NODE) return null;

    const text = node.textContent || '';
    const offset = Math.min(range.startOffset, text.length);
    if (offset >= text.length) return null;

    const ch = text[offset];
    if (!isWordChar(ch) || isScanBoundary(ch) || isFurigana(node)) return null;

    if (isCJK(ch)) {
      // Collect forward text across nodes, skipping furigana (<rt>)
      const container = node.parentElement.closest('.entry-body, .headword, .gloss-content') || document.body;
      const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
        acceptNode: (n) => isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
      });

      walker.currentNode = node;
      let result = '';
      let currNode = node;
      let currOffset = offset;

      while (currNode && result.length < MAX_SCAN_CHARS) {
        const content = currNode.textContent || '';
        const slice = content.slice(currOffset);

        for (let i = 0; i < slice.length && result.length < MAX_SCAN_CHARS; i++) {
          const char = slice[i];
          if (isScanBoundary(char)) {
            return result.length > 0 ? result : null;
          }
          result += char;
        }

        if (result.length >= MAX_SCAN_CHARS) break;
        currNode = walker.nextNode();
        currOffset = 0;
      }
      return result.length > 0 ? result : null;
    }

    // Space-separated: expand to word boundaries
    let start = offset;
    let end = offset;
    while (start > 0 && isWordChar(text[start - 1]) && !isScanBoundary(text[start - 1])) start--;
    while (end < text.length && isWordChar(text[end]) && !isScanBoundary(text[end])) end++;
    const word = text.slice(start, end).trim();
    return word.length > 0 ? word : null;
  }

  // ── Tab bar ───────────────────────────────────────────────────────────────

  let _tabs = [];          // [{label, active}]
  let _tabsEl = null;      // the .lookup-tabs DOM node

  function getOrCreateTabsEl() {
    if (_tabsEl && _tabsEl.parentElement) return _tabsEl;
    _tabsEl = document.createElement('div');
    _tabsEl.id = 'lookup-tabs';
    _tabsEl.className = 'lookup-tabs';
    return _tabsEl;
  }

  let _navMode = 'tabs';

  function renderTabBar(tabs, navMode) {
    if (navMode) _navMode = navMode;
    _tabs = Array.isArray(tabs) ? tabs : [];
    const container = document.getElementById('entries');
    if (!container) return;

    if (_tabs.length <= 1) {
      // Hide / remove the tab bar when there is only one (or zero) entries
      if (_tabsEl && _tabsEl.parentElement) _tabsEl.remove();
      return;
    }

    const el = getOrCreateTabsEl();
    el.textContent = '';

    if (_navMode === 'stack') {
      const btn = document.createElement('button');
      btn.className = 'lookup-tab stack-button';
      btn.title = 'Back';
      
      const label = document.createElement('span');
      label.textContent = '← Back';
      btn.appendChild(label);
      
      btn.onclick = (e) => {
        e.stopPropagation();
        navigateTo('hoshi://back');
      };
      
      el.appendChild(btn);
    } else {
      _tabs.forEach((tab, i) => {
        const btn = document.createElement('button');
        btn.className = 'lookup-tab' + (tab.active ? ' active' : '');
        btn.title = tab.label;
        btn.setAttribute('data-tab-index', String(i));

        const label = document.createElement('span');
        label.textContent = tab.label.length > 12 ? tab.label.slice(0, 12) + '…' : tab.label;
        btn.appendChild(label);

        if (!tab.active) {
          // Show a small × to indicate the tab is closeable / navigatable
          btn.onclick = (e) => {
            e.stopPropagation();
            navigateTo('hoshi://tab?index=' + i);
          };
        }

        el.appendChild(btn);
      });
    }

    // Prepend tab bar before first non-tab child
    if (!el.parentElement) {
      container.insertBefore(el, container.firstChild);
    }

    // Auto-scroll the active tab into view
    requestAnimationFrame(() => {
      const activeBtn = el.querySelector('.lookup-tab.active');
      if (activeBtn) activeBtn.scrollIntoView({block: 'nearest', inline: 'center', behavior: 'smooth'});
    });
  }

  // ── Navigation helper ─────────────────────────────────────────────────────

  function navigateTo(url) {
    // Using location.href is the most reliable way to fire shouldOverrideUrlLoading
    window.location.href = url;
  }

  // ── Touch / tap listener (delegated on document) ──────────────────────────

  let _lookupEnabled = false;
  let _touchStartX = 0;
  let _touchStartY = 0;
  const TAP_MOVE_THRESHOLD = 10;  // px — ignore if finger moved too far (scroll)

  function installTapListener() {
    if (_listenersInstalled) return;
    _listenersInstalled = true;

    document.addEventListener('click', (e) => {
      // If the user has an active text selection, don't trigger a lookup.
      // This is a safety check, although 'click' shouldn't fire on long-press.
      if (window.getSelection().toString().trim().length > 0) return;

      // Skip interactive controls — buttons, dict tags, inflection toggles, etc.
      const target = e.target;
      if (!target) return;
      if (target.closest('button, .anki-add-btn, .lookup-tab, .inflection-toggle, .tag')) return;

      const word = extractTextAtPoint(e.clientX, e.clientY);
      if (!word) return;

      navigateTo('hoshi://lookup?q=' + encodeURIComponent(word));
    }, {passive: false});

    document.addEventListener('selectionchange', () => {
      const selection = window.getSelection();
      if (selection && !selection.isCollapsed) {
        _lastSelection = selection.toString();
      }
    });
  }

  function mediaCandidates(path) {
    const list = new Set();
    const add = (value) => {
      if (!value) return;
      list.add(value);
      list.add(value.replace(/\\/g, '/'));
      if (value.startsWith('./')) list.add(value.slice(2));
      if (value.startsWith('/')) list.add(value.slice(1));
    };
    add(path);
    try {
      add(decodeURIComponent(path));
    } catch (e) {
      // ignore malformed URI paths
    }
    return Array.from(list);
  }

  function resolveMediaSrc(mediaMap, dictName, path) {
    if (!path) return null;
    for (const candidate of mediaCandidates(path)) {
      const key = `${dictName}\u0000${candidate}`;
      if (Object.prototype.hasOwnProperty.call(mediaMap, key)) {
        return mediaMap[key];
      }
    }
    return null;
  }

  const POS_TAGS = new Set(['n', 'vs', 'vi', 'vt', 'adj-i', 'adj-na', 'adv', 'v1', 'v5k', 'v5s', 'v5m', 'v5n', 'v5r', 'v5t', 'exp', 'int']);
  const ARCH_TAGS = new Set(['arch', 'obs', 'rare', 'ok', 'oK']);
  const POPULAR_TAGS = new Set(['P', 'popular', 'frequent', 'common']);



  function resolveTagCategory(label, defaultCategory) {
    if (defaultCategory && defaultCategory !== 'default') return defaultCategory;
    const t = label.toLowerCase();
    if (t.includes('arch') || t.includes('obs') || t.includes('hist')) return 'archaism';
    if (t.includes('P') || t.includes('popular')) return 'popular';
    if (t.includes('freq')) return 'frequent';
    if (t.includes('col') || t.includes('pol') || t.includes('hon') || t.includes('fam')) return 'style';
    if (t.includes('dial')) return 'dialect';
    if (t.includes('v5') || t.includes('v1') || t.includes('adj') || t.includes('n') || t.includes('adv')) return 'partOfSpeech';
    return 'default';
  }

  function createTag(label, category) {
    const resolvedCategory = resolveTagCategory(label, category);
    const span = document.createElement('span');
    span.className = 'tag';
    span.dataset.category = resolvedCategory;
    span.dataset.details = label;

    const inner = document.createElement('span');
    inner.className = 'tag-label';

    const content = document.createElement('span');
    content.className = 'tag-label-content';
    content.textContent = label;

    inner.appendChild(content);
    span.appendChild(inner);
    return span;
  }

  function createTagWithBody(label, body, category) {
    const span = createTag(label, category);
    span.classList.add('tag-has-body');
    // Round right of label when body follows
    const labelEl = span.querySelector('.tag-label');
    if (labelEl) {
      labelEl.style.borderTopRightRadius = '0';
      labelEl.style.borderBottomRightRadius = '0';
    }
    const bodyNode = document.createElement('span');
    bodyNode.className = 'tag-body';
    const bodyContent = document.createElement('span');
    bodyContent.className = 'tag-body-content';
    bodyContent.textContent = body;
    bodyNode.appendChild(bodyContent);
    span.appendChild(bodyNode);
    return span;
  }

  function buildGlossaryNode(rawGlossary, dictName, mediaMap) {
    const parsed = parseMaybeJson(rawGlossary);
    if (parsed === null) {
      const span = document.createElement('span');
      span.className = 'gloss-plain-text';
      const normalized = normalizePlainText(rawGlossary);
      if (containsJapaneseText(normalized)) {
        span.lang = 'ja';
      }
      appendTextWithLineBreaks(span, normalized);
      return span;
    }
    return renderStructured(parsed, dictName, mediaMap);
  }

  function containsJapaneseText(text) {
    return typeof text === 'string' && /[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff]/.test(text);
  }

  function getNodeText(node) {
    if (node === null || typeof node === 'undefined') return '';
    if (typeof node === 'string' || typeof node === 'number' || typeof node === 'boolean') {
      return String(node);
    }
    if (Array.isArray(node)) {
      return node.map((item) => getNodeText(item)).join('');
    }
    if (typeof node !== 'object') return '';
    if (node && node.data && node.data.content === 'attribution') return '';
    if (node.type === 'structured-content') return getNodeText(node.content);
    return getNodeText(node.content);
  }

  function normalizeTableContent(content) {
    const items = Array.isArray(content) ? content : [content];
    const hasRows = items.some((item) => item && item.tag === 'tr');
    if (!hasRows) return content;
    const hasSections = items.some((item) => item && (item.tag === 'tbody' || item.tag === 'thead' || item.tag === 'tfoot'));
    if (hasSections) return content;
    return [{tag: 'tbody', content: items}];
  }

  function renderStructured(content, dictName, mediaMap) {
    const fragment = document.createDocumentFragment();
    appendStructured(fragment, content, dictName, mediaMap, null);
    const wrapper = document.createElement('span');
    wrapper.className = 'structured-content';
    wrapper.appendChild(fragment);
    return wrapper;
  }

  // Registry-based dispatch for known tags; unknown valid tags still fall back to generic renderer.
  const GENERIC_ELEMENT_TAGS = [
    'br', 'ruby', 'rt', 'rp', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
    'div', 'span', 'ol', 'ul', 'li', 'details', 'summary', 'p', 'strong', 'em',
    'b', 'i', 'u', 'small', 'sub', 'sup', 'code', 'pre', 'blockquote', 's', 'mark',
    'ins', 'del', 'kbd', 'samp', 'var', 'time', 'q', 'cite', 'abbr'
  ];

  function getTagInfo(node, fallbackTag) {
    const raw = typeof node.tag === 'string' && node.tag.trim().length > 0
      ? node.tag.trim()
      : fallbackTag;
    const lower = raw.toLowerCase();
    return {raw, lower};
  }

  function createGenericElementHandler(defaultTagLower) {
    return (node, dictName, mediaMap, language) => {
      const {raw, lower} = getTagInfo(node, defaultTagLower);
      return createGenericStructuredElement(node, raw, lower, dictName, mediaMap, language);
    };
  }

  function createElementRegistry() {
    const registry = {
      img: (node, dictName, mediaMap) => createImageNode(node, dictName, mediaMap),
      a: (node, dictName, mediaMap, language) => createLinkNode(node, dictName, mediaMap, language)
    };

    for (const tag of GENERIC_ELEMENT_TAGS) {
      registry[tag] = createGenericElementHandler(tag);
    }

    return Object.freeze(registry);
  }

  const ELEMENT_REGISTRY = createElementRegistry();

  function isValidTagName(tagName) {
    return /^[a-z][a-z0-9:-]*$/i.test(tagName);
  }

  const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
  const svgTags = new Set([
    'svg', 'g', 'path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon',
    'text', 'tspan', 'textpath', 'defs', 'symbol', 'use', 'marker', 'pattern',
    'mask', 'clippath', 'lineargradient', 'radialgradient', 'stop', 'view', 'foreignobject'
  ]);

  const svgAttributeMap = {
    viewBox: 'viewBox',
    preserveAspectRatio: 'preserveAspectRatio',
    width: 'width',
    height: 'height',
    x: 'x',
    y: 'y',
    x1: 'x1',
    y1: 'y1',
    x2: 'x2',
    y2: 'y2',
    cx: 'cx',
    cy: 'cy',
    r: 'r',
    rx: 'rx',
    ry: 'ry',
    d: 'd',
    points: 'points',
    fill: 'fill',
    stroke: 'stroke',
    strokeWidth: 'stroke-width',
    strokeLinecap: 'stroke-linecap',
    strokeLinejoin: 'stroke-linejoin',
    strokeMiterlimit: 'stroke-miterlimit',
    strokeDasharray: 'stroke-dasharray',
    strokeDashoffset: 'stroke-dashoffset',
    transform: 'transform',
    opacity: 'opacity',
    fillRule: 'fill-rule',
    clipRule: 'clip-rule',
    xmlns: 'xmlns',
    href: 'href'
  };

  function isSvgTag(tagNameLower) {
    return svgTags.has(tagNameLower);
  }

  function createElementForTag(tagName, isSvgElement) {
    return isSvgElement
      ? document.createElementNS(SVG_NAMESPACE, tagName)
      : document.createElement(tagName);
  }

  function applySvgAttributes(element, node) {
    for (const [sourceKey, attrName] of Object.entries(svgAttributeMap)) {
      const value = node[sourceKey];
      if (typeof value === 'string' || typeof value === 'number') {
        element.setAttribute(attrName, String(value));
      }
    }

    // Keep SVG sizing behavior data-driven from dictionary styles/content.
  }

  function applyCommonStructuredAttributes(element, node) {
    if (node.lang && typeof node.lang === 'string') {
      element.lang = node.lang;
    }
    if (node.title && typeof node.title === 'string') {
      element.title = node.title;
    }
    applyDataAttributes(element, node.data);
    applyStyle(element, node.style);
  }

  function createGenericStructuredElement(node, tagNameRaw, tagNameLower, dictName, mediaMap, language) {
    const svgElement = isSvgTag(tagNameLower);
    const elementTagName = svgElement ? tagNameRaw : tagNameLower;
    const element = createElementForTag(elementTagName, svgElement);
    element.classList.add(`gloss-sc-${tagNameLower}`);

    if (svgElement) {
      applySvgAttributes(element, node);
    }

    if (node.lang && typeof node.lang === 'string') {
      language = node.lang;
    }

    if ((tagNameLower === 'th' || tagNameLower === 'td') && Number.isFinite(node.colSpan)) {
      element.colSpan = node.colSpan;
    }
    if ((tagNameLower === 'th' || tagNameLower === 'td') && Number.isFinite(node.rowSpan)) {
      element.rowSpan = node.rowSpan;
    }
    if (tagNameLower === 'details' && node.open === true) {
      element.setAttribute('open', '');
    }

    applyCommonStructuredAttributes(element, node);

    if (DEBUG_VERBOSE_NODES && node && node.data && (Object.prototype.hasOwnProperty.call(node.data, 'content') || Object.prototype.hasOwnProperty.call(node.data, 'class'))) {
      debugLog('createGenericStructuredElement.node', {
        dictName,
        tag: tagNameLower,
        className: element.className,
        dataContent: Object.prototype.hasOwnProperty.call(node.data, 'content') ? String(node.data.content) : '',
        dataClass: Object.prototype.hasOwnProperty.call(node.data, 'class') ? String(node.data.class) : ''
      });
    }

    const contentToRender = tagNameLower === 'table' ? normalizeTableContent(node.content) : node.content;

    if (tagNameLower !== 'br') {
      appendStructured(element, contentToRender, dictName, mediaMap, language);
    }

    if (tagNameLower === 'table') {
      const wrapper = document.createElement('div');
      wrapper.className = 'gloss-sc-table-container';
      wrapper.appendChild(element);
      return wrapper;
    }

    return element;
  }

  function renderStructuredObjectNode(content, dictName, mediaMap, language) {
    if (!content || typeof content !== 'object') return null;
    if (content.data && content.data.content === 'attribution') return null;

    let node = content;
    if (node.type === 'structured-content') {
      return {kind: 'nested', content: node.content};
    }
    if (node.type === 'image' && !node.tag) {
      node = Object.assign({}, node, {tag: 'img'});
    }

    const tagNameRaw = typeof node.tag === 'string' ? node.tag.trim() : '';
    if (!tagNameRaw) {
      return {kind: 'nested', content: node.content};
    }

    if (!isValidTagName(tagNameRaw)) {
      return {kind: 'nested', content: node.content};
    }

    const tagNameLower = tagNameRaw.toLowerCase();
    const handler = ELEMENT_REGISTRY[tagNameLower];
    if (handler) {
      return {kind: 'node', node: handler(node, dictName, mediaMap, language)};
    }

    return {
      kind: 'node',
      node: createGenericStructuredElement(node, tagNameRaw, tagNameLower, dictName, mediaMap, language)
    };
  }

  function appendStructured(parent, content, dictName, mediaMap, language) {
    if (content === null || typeof content === 'undefined') return;

    if (typeof content === 'string' || typeof content === 'number' || typeof content === 'boolean') {
      appendTextWithLineBreaks(parent, content);
      return;
    }

    if (Array.isArray(content)) {
      const isStringArray = content.every((item) => typeof item === 'string');
      const insideSpan = parent && parent.tagName === 'SPAN';
      if (isStringArray && content.length > 1 && !insideSpan) {
        const ul = document.createElement('ul');
        ul.classList.add('glossary-list');
        content.forEach((child) => {
          const li = document.createElement('li');
          appendTextWithLineBreaks(li, child);
          ul.appendChild(li);
        });
        parent.appendChild(ul);
        return;
      }

      for (const item of content) appendStructured(parent, item, dictName, mediaMap, language);
      return;
    }

    if (typeof content !== 'object') return;

    const rendered = renderStructuredObjectNode(content, dictName, mediaMap, language);
    if (!rendered) return;
    if (rendered.kind === 'nested') {
      appendStructured(parent, rendered.content, dictName, mediaMap, language);
      return;
    }
    parent.appendChild(rendered.node);
  }

  function createLinkNode(node, dictName, mediaMap, language) {
    const href = sanitizeHref(node.href);
    const internal = href && href.startsWith('?');

    const a = document.createElement('span');
    a.className = 'gloss-link gloss-sc-a';
    a.dataset.external = String(!internal);
    if (typeof node.title === 'string') a.title = node.title;
    if (typeof node.lang === 'string') {
      a.lang = node.lang;
      language = node.lang;
    }

    applyCommonStructuredAttributes(a, node);

    if (DEBUG_VERBOSE_NODES) {
      debugLog('createLinkNode', {dictName, href, internal});
    }

    const text = document.createElement('span');
    text.className = 'gloss-link-text';
    appendStructured(text, node.content, dictName, mediaMap, language);
    a.appendChild(text);
    return a;
  }

  function createImageNode(node, dictName, mediaMap) {
    const path = typeof node.path === 'string' ? node.path : (typeof node.src === 'string' ? node.src : '');
    const src = resolveMediaSrc(mediaMap, dictName, path) || path;

    if (DEBUG_VERBOSE_NODES) {
      debugLog('createImageNode', {
        dictName,
        path,
        resolved: src,
        resolvedFromMap: src !== path
      });
    }

    const preferredWidth = Number(node.preferredWidth ?? (node.data && node.data.preferredWidth));
    const preferredHeight = Number(node.preferredHeight ?? (node.data && node.data.preferredHeight));
    const width = Number(node.width ?? (node.data && node.data.width) ?? 100);
    const height = Number(node.height ?? (node.data && node.data.height) ?? 100);

    const ratioWidth = Number.isFinite(preferredWidth) && preferredWidth > 0 ? preferredWidth : width;
    const ratioHeight = Number.isFinite(preferredHeight) && preferredHeight > 0 ? preferredHeight : height;
    const invAspectRatio = Math.max(0.01, ratioHeight / Math.max(1, ratioWidth));

    const units = typeof node.sizeUnits === 'string'
      ? node.sizeUnits
      : (node.data && typeof node.data.sizeUnits === 'string' ? node.data.sizeUnits : 'px');

    const usedWidth = Number.isFinite(preferredWidth) && preferredWidth > 0
      ? preferredWidth
      : (Number.isFinite(preferredHeight) && preferredHeight > 0 ? preferredHeight / invAspectRatio : width);

    const imageRendering = typeof node.imageRendering === 'string'
      ? node.imageRendering
      : (node.pixelated === true ? 'pixelated' : 'auto');

    const link = document.createElement('span');
    link.className = 'gloss-image-link gloss-sc-a';
    link.dataset.path = path;
    link.dataset.imageLoadState = src && src.startsWith('data:') ? 'loaded' : 'unloaded';
    link.dataset.hasAspectRatio = 'true';
    link.dataset.imageRendering = imageRendering;
    link.dataset.appearance = typeof node.appearance === 'string' ? node.appearance : 'auto';
    link.dataset.background = String(node.background !== false);
    
    // Apply layout styles
    link.style.width = `${usedWidth}${units}`;
    link.style.maxWidth = '100%';
    if (typeof node.verticalAlign === 'string') {
      link.dataset.verticalAlign = node.verticalAlign;
      link.style.verticalAlign = node.verticalAlign;
    }
    if (typeof node.title === 'string') link.title = node.title;

    applyCommonStructuredAttributes(link, node);

    const container = document.createElement('span');
    container.className = 'gloss-image-container';
    container.style.width = `${trimFloat(usedWidth)}${units}`;
    container.style.maxWidth = '100%';
    if (typeof node.border === 'string') container.style.border = node.border;
    if (typeof node.borderRadius === 'string') container.style.borderRadius = node.borderRadius;

    const sizer = document.createElement('span');
    sizer.className = 'gloss-image-sizer';
    sizer.style.paddingTop = `${trimFloat(invAspectRatio * 100)}%`;
    container.appendChild(sizer);

    const bg = document.createElement('span');
    bg.className = 'gloss-image-background';
    if (src) bg.style.setProperty('--image', `url("${src}")`);
    container.appendChild(bg);

    const img = document.createElement('img');
    img.className = 'gloss-image gloss-sc-img';
    if (src) img.src = src;
    img.loading = 'lazy';
    img.style.maxWidth = '100%';
    img.decoding = 'async';
    img.style.imageRendering = imageRendering;
    if (typeof node.title === 'string') img.alt = node.title;
    container.appendChild(img);

    link.appendChild(container);
    return link;
  }

  function isKana(ch) {
    return (ch >= '\u3041' && ch <= '\u3096') || (ch >= '\u30a1' && ch <= '\u30fa') || ch === '々' || ch === 'ヶ';
  }

  function distributeFurigana(expression, reading) {
    if (!reading || reading === expression) {
      return [{text: expression, reading: ''}];
    }

    let start = 0;
    while (start < expression.length && start < reading.length && expression[start] === reading[start]) {
      start++;
    }

    let endExpression = expression.length - 1;
    let endReading = reading.length - 1;
    while (endExpression >= start && endReading >= start && expression[endExpression] === reading[endReading]) {
      endExpression--;
      endReading--;
    }

    const segments = [];
    if (start > 0) {
      segments.push({text: expression.substring(0, start), reading: ''});
    }

    if (endExpression >= start) {
      segments.push({
        text: expression.substring(start, endExpression + 1),
        reading: reading.substring(start, endReading + 1)
      });
    }

    if (endExpression < expression.length - 1) {
      segments.push({text: expression.substring(endExpression + 1), reading: ''});
    }

    return segments;
  }

  function createHeadwordNode(expression, reading, termTags) {
    const headword = document.createElement('span');
    headword.className = 'headword';
    
    const popularityClass = (() => {
        const t = termTags ? termTags.toLowerCase() : '';
        if (t.includes('popular') || t.includes(' p ')) return 'popular';
        if (t.includes('rare') || t.includes('arch') || t.includes('obs')) return 'rare';
        return '';
    })();

    const segments = distributeFurigana(expression, reading);

    for (const segment of segments) {
      if (segment.reading) {
        const ruby = document.createElement('ruby');
        ruby.className = 'headword-text-container headword-term';
        if (popularityClass) ruby.classList.add(popularityClass);
        ruby.textContent = segment.text;

        const rt = document.createElement('rt');
        rt.className = 'headword-furigana';
        rt.textContent = segment.reading;

        ruby.appendChild(rt);
        headword.appendChild(ruby);
      } else {
        const termNode = document.createElement('span');
        termNode.className = 'headword-term';
        if (popularityClass) termNode.classList.add(popularityClass);
        termNode.textContent = segment.text;
        headword.appendChild(termNode);
      }
    }

    if (termTags) {
      const tagList = document.createElement('span');
      tagList.className = 'headword-tag-list';
      termTags.split(/\s+/).forEach(t => {
        if (t) tagList.appendChild(createTag(t));
      });
      headword.appendChild(tagList);
    }

    return headword;
  }

  function appendInflectionSection(body, rules, process) {
    if (!process) return;

    const container = document.createElement('div');
    container.className = 'inflection-toggle';

    const toggle = document.createElement('span');
    toggle.className = 'inflection-toggle-label';
    toggle.textContent = 'Deinflection ▸';
    container.appendChild(toggle);

    const details = document.createElement('div');
    details.className = 'inflection-details';
    details.style.display = 'none';
    const chains = document.createElement('ul');
    chains.className = 'inflection-rule-chains';
    const li = document.createElement('li');
    li.textContent = process;
    chains.appendChild(li);
    details.appendChild(chains);

    let expanded = false;
    container.onclick = () => {
      expanded = !expanded;
      details.style.display = expanded ? 'block' : 'none';
      toggle.textContent = expanded ? 'Deinflection ▾' : 'Deinflection ▸';
    };

    container.appendChild(details);
    body.appendChild(container);
  }

  function createAudioButton(expression, reading) {
    const audioBtn = document.createElement('button');
    audioBtn.className = 'word-audio-btn';
    audioBtn.textContent = '🔊';
    audioBtn.title = 'Play Word Audio';
    audioBtn.dataset.expression = expression;
    audioBtn.dataset.reading = reading;
    
    audioBtn.onclick = (e) => {
      e.stopPropagation();
      if (typeof WordAudioBridge !== 'undefined') {
        const callbackId = 'audio_' + Date.now();
        audioBtn.classList.add('loading');
        
        window.DictionaryRenderer.onAudioResults = (id, results) => {
          if (id !== callbackId) return;
          audioBtn.classList.remove('loading');
          
          if (results && results.length > 0) {
            WordAudioBridge.playAudio(results[0].url);
          } else {
            audioBtn.classList.add('error');
            setTimeout(() => audioBtn.classList.remove('error'), 1000);
          }
        };
        
        WordAudioBridge.fetchAudio(expression, reading, callbackId);
      }
    };
    return audioBtn;
  }

  function appendDefinitionsSection(body, glossaries, mediaMap, group) {
    if (!glossaries || glossaries.length === 0) return;

    if (!group) {
        // FLAT LIST (Ungrouped)
        const defSection = document.createElement('div');
        defSection.className = 'entry-body-section';
        const ol = document.createElement('ol');
        ol.className = 'definition-list';
        ol.dataset.count = String(glossaries.length);
        
        glossaries.forEach(gloss => {
            const li = document.createElement('li');
            li.className = 'definition-item ungrouped-item';
            
            const content = document.createElement('div');
            content.className = 'definition-item-content';
            
            const tagList = document.createElement('div');
            tagList.className = 'definition-tag-list';
            if (gloss.dictName) {
              const dictTag = createTag(gloss.dictName, 'dictionary');
              dictTag.style.cursor = 'pointer';
              dictTag.onclick = (e) => {
                e.stopPropagation();
                const entryIdx = li.closest('article').dataset.index;
                const current = _selectedDictionaries[entryIdx];
                li.closest('article').querySelectorAll('.tag[data-category="dictionary"]').forEach(t => t.classList.remove('selected'));
                if (current === gloss.dictName) {
                  delete _selectedDictionaries[entryIdx];
                } else {
                  _selectedDictionaries[entryIdx] = gloss.dictName;
                  dictTag.classList.add('selected');
                }
              };
              tagList.appendChild(dictTag);
            }
            if (gloss.definitionTags) {
                gloss.definitionTags.split(/\s+/).forEach(t => { if(t) tagList.appendChild(createTag(t)); });
            }
            content.appendChild(tagList);

            const glossContainer = document.createElement('div');
            glossContainer.className = 'gloss-content';
            glossContainer.appendChild(buildGlossaryNode(String(gloss.glossary || ''), gloss.dictName, mediaMap));
            content.appendChild(glossContainer);
            li.appendChild(content);
            ol.appendChild(li);
        });
        defSection.appendChild(ol);
        body.appendChild(defSection);
        return;
    }

    // GROUPED BY DICTIONARY
    const groups = new Map();
    glossaries.forEach((gloss) => {
      const name = gloss.dictName || 'Unknown';
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(gloss);
    });

    for (const [dictName, dictGlossaries] of groups) {
      const dictSection = document.createElement('div');
      dictSection.className = 'entry-body-section dictionary-group';
      dictSection.dataset.dictionary = dictName;

      const dictHeader = document.createElement('div');
      dictHeader.className = 'dictionary-header';
      if (dictName) {
        const dictTag = createTag(dictName, 'dictionary');
        dictTag.style.cursor = 'pointer';
        dictTag.onclick = (e) => {
          e.stopPropagation();
          const entryIdx = dictSection.closest('article').dataset.index;
          const current = _selectedDictionaries[entryIdx];
          dictSection.closest('article').querySelectorAll('.tag[data-category="dictionary"]').forEach(t => t.classList.remove('selected'));
          if (current === dictName) {
            delete _selectedDictionaries[entryIdx];
          } else {
            _selectedDictionaries[entryIdx] = dictName;
            dictTag.classList.add('selected');
          }
        };
        dictHeader.appendChild(dictTag);
      }
      
      if (dictGlossaries[0].termTags) {
        dictGlossaries[0].termTags.split(/\s+/).forEach(t => {
          if (t) dictHeader.appendChild(createTag(t));
        });
      }
      dictSection.appendChild(dictHeader);

      const ol = document.createElement('ol');
      ol.className = 'definition-list entry-body-section-content'; // Added alias for masonry CSS
      ol.dataset.count = String(dictGlossaries.length);

      dictGlossaries.forEach((gloss) => {
        const li = document.createElement('li');
        li.className = 'definition-item';
        li.dataset.dictionary = dictName; // Added for colorizer compatibility
        
        const content = document.createElement('div');
        content.className = 'definition-item-content';
        
        if (gloss.definitionTags) {
          const tagList = document.createElement('div');
          tagList.className = 'definition-tag-list';
          gloss.definitionTags.split(/\s+/).forEach(t => {
            if (t) tagList.appendChild(createTag(t));
          });
          content.appendChild(tagList);
        }

        const glossContainer = document.createElement('div');
        glossContainer.className = 'gloss-content';
        glossContainer.appendChild(buildGlossaryNode(String(gloss.glossary || ''), dictName, mediaMap));
        content.appendChild(glossContainer);
        
        li.appendChild(content);
        ol.appendChild(li);
      });

      dictSection.appendChild(ol);
      body.appendChild(dictSection);
    }
  }

  function appendFrequenciesSection(body, frequencies, showHarmonic) {
    if (frequencies.length === 0) return;

    const section = document.createElement('div');
    section.className = 'entry-body-section frequency-top-section';
    section.dataset.sectionType = 'frequencies';

    // If showHarmonic is true, calculate and display harmonic mean instead of full list
    if (showHarmonic) {
      const numbers = [];
      const seen = new Set();
      for (const group of frequencies) {
        if (seen.has(group.dictName)) continue;
        seen.add(group.dictName);
        const items = Array.isArray(group.frequencies) ? group.frequencies : [];
        for (const item of items) {
          if (item && item.value > 0) {
            numbers.push(item.value);
            break;
          }
        }
      }
      
      if (numbers.length > 0) {
        const n = numbers.length;
        let harmonic = 0;
        for (const num of numbers) {
          harmonic += 1 / num;
        }
        harmonic = Math.floor(n / harmonic);

        const tag = createTagWithBody('freq', `harmonic: ${harmonic}`, 'frequency');
        section.appendChild(tag);
        body.appendChild(section);
      }
      return;
    }

    // Default: show compact top-row chips like Yomitan frequency groups.
    for (const group of frequencies) {
      const dictName = String(group.dictName || '').trim();
      const items = Array.isArray(group.frequencies) ? group.frequencies : [];
      const values = [];
      items.forEach((item, idx) => {
        const value = item && item.displayValue ? item.displayValue : String(item && item.value ? item.value : '');
        if (value) {
          values.push(value);
        }
      });

      if (!dictName || values.length === 0) {
        continue;
      }

      const chip = createTagWithBody(dictName, values.join(', '), 'frequency');
      chip.classList.add('frequency-group-item');
      section.appendChild(chip);
    }

    if (section.childElementCount > 0) {
      body.appendChild(section);
    }
  }



  // ── Pitch accent helpers (exact port of Yomitan's japanese.js) ───────────

  // Exact small-kana set from japanese.js — Set lookup is faster than RegExp
  // and this matches the canonical source character-for-character.
  const SMALL_KANA_SET = new Set('ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ');

  // DIACRITIC_MAPPING — built from the same kana triplet string used in
  // japanese.js.  Each triplet is: base, dakuten-form, handakuten-form ('-' = none).
  // Maps voiced/semi-voiced kana → {character (unvoiced base), type}.
  const _kana = 'うゔ-かが-きぎ-くぐ-けげ-こご-さざ-しじ-すず-せぜ-そぞ-ただ-ちぢ-つづ-てで-とど-はばぱひびぴふぶぷへべぺほぼぽワヷ-ヰヸ-ウヴ-ヱヹ-ヲヺ-カガ-キギ-クグ-ケゲ-コゴ-サザ-シジ-スズ-セゼ-ソゾ-タダ-チヂ-ツヅ-テデ-トド-ハバパヒビピフブプヘベペホボポ';
  const DIACRITIC_MAPPING = new Map();
  for (let _i = 0, _ii = _kana.length; _i < _ii; _i += 3) {
    const _base     = _kana[_i];
    const _dakuten  = _kana[_i + 1];
    const _handakuten = _kana[_i + 2];
    DIACRITIC_MAPPING.set(_dakuten,   {character: _base, type: 'dakuten'});
    if (_handakuten !== '-') {
      DIACRITIC_MAPPING.set(_handakuten, {character: _base, type: 'handakuten'});
    }
  }

  /**
   * getKanaMorae — exact port of japanese.js getKanaMorae.
   * Uses SMALL_KANA_SET (a Set) instead of a RegExp for correctness and speed.
   * @param {string} text
   * @returns {string[]}
   */
  function getMorae(text) {
    const morae = [];
    let i;
    for (const c of text) {
      if (SMALL_KANA_SET.has(c) && (i = morae.length) > 0) {
        morae[i - 1] += c;
      } else {
        morae.push(c);
      }
    }
    return morae;
  }

  /**
   * isMoraPitchHigh — exact port of japanese.js isMoraPitchHigh.
   * String pitch values: index into the H/L string directly, no clamping
   * (out-of-bounds → undefined !== 'H' → false, matching JS source behaviour).
   * @param {number} moraIndex
   * @param {number|string} pitchAccentValue
   * @returns {boolean}
   */
  function isMoraPitchHigh(moraIndex, pitchAccentValue) {
    if (typeof pitchAccentValue === 'string') {
      return pitchAccentValue[moraIndex] === 'H';
    }
    switch (pitchAccentValue) {
      case 0:  return (moraIndex > 0);
      case 1:  return (moraIndex < 1);
      default: return (moraIndex > 0 && moraIndex < pitchAccentValue);
    }
  }

  /**
   * getKanaDiacriticInfo — exact port of japanese.js getKanaDiacriticInfo.
   * Returns {character, type} for voiced/semi-voiced kana, null otherwise.
   * The `type` field ('dakuten' | 'handakuten') is included for full parity
   * even though the nasal indicator only uses `character`.
   * @param {string} character
   * @returns {{character: string, type: string}|null}
   */
  function getKanaDiacriticInfo(character) {
    const info = DIACRITIC_MAPPING.get(character);
    return typeof info !== 'undefined' ? {character: info.character, type: info.type} : null;
  }

  // ── SVG graph (Yomitan createPronunciationGraph parity) ──────────────────

  function _graphCircle(className, x, y, r) {
    const c = document.createElementNS(SVG_NAMESPACE, 'circle');
    c.setAttribute('class', className);
    c.setAttribute('cx', String(x));
    c.setAttribute('cy', String(y));
    c.setAttribute('r', String(r));
    return c;
  }

  /**
   * Exact port of PronunciationGenerator.createPronunciationGraph.
   * viewBox: 0 0 (50*(n+1)) 100  — dots at y=25 (high) or y=75 (low).
   */
  function createPitchDiagram(morae, pitchPositions) {
    const ii = morae.length;
    const svg = document.createElementNS(SVG_NAMESPACE, 'svg');
    svg.setAttribute('xmlns', SVG_NAMESPACE);
    svg.setAttribute('class', 'pronunciation-graph');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute('viewBox', `0 0 ${50 * (ii + 1)} 100`);

    if (ii <= 0) return svg;

    // Two paths appended first so dots render on top (matches Yomitan order).
    const path1 = document.createElementNS(SVG_NAMESPACE, 'path');
    svg.appendChild(path1);
    const path2 = document.createElementNS(SVG_NAMESPACE, 'path');
    svg.appendChild(path2);

    const pathPoints = [];
    for (let i = 0; i < ii; i++) {
      const highPitch     = isMoraPitchHigh(i,     pitchPositions);
      const highPitchNext = isMoraPitchHigh(i + 1, pitchPositions);
      const x = i * 50 + 25;
      const y = highPitch ? 25 : 75;

      if (highPitch && !highPitchNext) {
        // Downstep dot: outer ring + inner filled circle
        svg.appendChild(_graphCircle('pronunciation-graph-dot-downstep1', x, y, 15));
        svg.appendChild(_graphCircle('pronunciation-graph-dot-downstep2', x, y, 5));
      } else {
        svg.appendChild(_graphCircle('pronunciation-graph-dot', x, y, 15));
      }
      pathPoints.push(`${x} ${y}`);
    }

    path1.setAttribute('class', 'pronunciation-graph-line');
    path1.setAttribute('d', `M${pathPoints.join(' L')}`);

    // Tail segment from last mora to the "next mora" position
    pathPoints.splice(0, ii - 1);
    {
      const highPitch = isMoraPitchHigh(ii, pitchPositions);
      const x = ii * 50 + 25;
      const y = highPitch ? 25 : 75;
      // Triangle marker at tail end
      const tri = document.createElementNS(SVG_NAMESPACE, 'path');
      tri.setAttribute('class', 'pronunciation-graph-triangle');
      tri.setAttribute('d', 'M0 13 L15 -13 L-15 -13 Z');
      tri.setAttribute('transform', `translate(${x},${y})`);
      svg.appendChild(tri);
      pathPoints.push(`${x} ${y}`);
    }

    path2.setAttribute('class', 'pronunciation-graph-line-tail');
    path2.setAttribute('d', `M${pathPoints.join(' L')}`);

    return svg;
  }

  // ── Downstep notation [N] span (Yomitan createPronunciationDownstepPosition) ─

  function createDownstepNotation(pitchPositions) {
    const pos = String(pitchPositions);
    const n1 = document.createElement('span');
    n1.className = 'pronunciation-downstep-notation';
    n1.dataset.downstepPosition = pos;

    let n2 = document.createElement('span');
    n2.className = 'pronunciation-downstep-notation-prefix';
    n2.textContent = '[';
    n1.appendChild(n2);

    n2 = document.createElement('span');
    n2.className = 'pronunciation-downstep-notation-number';
    n2.textContent = pos;
    n1.appendChild(n2);

    n2 = document.createElement('span');
    n2.className = 'pronunciation-downstep-notation-suffix';
    n2.textContent = ']';
    n1.appendChild(n2);

    return n1;
  }

  // ── Mora text line (Yomitan createPronunciationText parity) ──────────────

  /**
   * Full port of PronunciationGenerator.createPronunciationText.
   * Supports nasalPositions and devoicePositions arrays (1-based, matching
   * Yomitan's convention where position 1 = first mora).
   * Each mora gets:
   *   - data-position, data-pitch, data-pitchNext on the .pronunciation-mora span
   *   - one .pronunciation-character span per character in the mora
   *   - optional .pronunciation-devoice-indicator span (devoiced morae)
   *   - optional .pronunciation-nasal-indicator + .pronunciation-nasal-diacritic
   *     wrapped in a .pronunciation-character-group (nasal morae)
   *   - .pronunciation-mora-line span (always last child, styled via CSS)
   */
  function createPitchTextLine(morae, pitchPositions, nasalPositions, devoicePositions) {
    const nasalSet   = (nasalPositions   && nasalPositions.length   > 0) ? new Set(nasalPositions)   : null;
    const devoiceSet = (devoicePositions && devoicePositions.length > 0) ? new Set(devoicePositions) : null;

    const container = document.createElement('span');
    container.className = 'pronunciation-text';

    for (let i = 0, ii = morae.length; i < ii; i++) {
      const i1 = i + 1; // 1-based position used by Yomitan for nasal/devoice sets
      const mora = morae[i];
      const highPitch     = isMoraPitchHigh(i,  pitchPositions);
      const highPitchNext = isMoraPitchHigh(i1, pitchPositions);
      const isNasal   = nasalSet   !== null && nasalSet.has(i1);
      const isDevoice = devoiceSet !== null && devoiceSet.has(i1);

      const moraSpan = document.createElement('span');
      moraSpan.className = 'pronunciation-mora';
      moraSpan.dataset.position = String(i);
      moraSpan.dataset.pitch     = highPitch     ? 'high' : 'low';
      moraSpan.dataset.pitchNext = highPitchNext ? 'high' : 'low';

      // One .pronunciation-character span per character in the mora
      const characterNodes = [];
      for (const ch of mora) {
        const charSpan = document.createElement('span');
        charSpan.className = 'pronunciation-character';
        charSpan.textContent = ch;
        moraSpan.appendChild(charSpan);
        characterNodes.push(charSpan);
      }

      // Devoice indicator — a dotted circle overlaid on the mora
      if (isDevoice) {
        moraSpan.dataset.devoice = 'true';
        const indicator = document.createElement('span');
        indicator.className = 'pronunciation-devoice-indicator';
        moraSpan.appendChild(indicator);
      }

      // Nasal indicator — replaces voiced kana with unvoiced base, adds
      // a hidden combining handakuten span + a visible small circle indicator,
      // all wrapped in a .pronunciation-character-group around the first char.
      if (isNasal && characterNodes.length > 0) {
        moraSpan.dataset.nasal = 'true';

        const group = document.createElement('span');
        group.className = 'pronunciation-character-group';

        const firstCharNode = characterNodes[0];
        const originalChar = firstCharNode.textContent;
        const diacriticInfo = getKanaDiacriticInfo(originalChar);
        if (diacriticInfo !== null) {
          moraSpan.dataset.originalText = mora;
          firstCharNode.dataset.originalText = originalChar;
          firstCharNode.textContent = diacriticInfo.character;
        }

        // Hidden combining handakuten (゜) — preserves copy-paste output
        const diacritic = document.createElement('span');
        diacritic.className = 'pronunciation-nasal-diacritic';
        diacritic.textContent = '\u309a';
        group.appendChild(diacritic);

        // Visible indicator dot
        const indicator = document.createElement('span');
        indicator.className = 'pronunciation-nasal-indicator';
        group.appendChild(indicator);

        // Replace firstCharNode in the mora span with the group, then
        // insert the character back as the first child of the group.
        firstCharNode.parentNode.replaceChild(group, firstCharNode);
        group.insertBefore(firstCharNode, group.firstChild);
      }

      // Pitch line — always the last child, displayed/hidden via CSS
      const line = document.createElement('span');
      line.className = 'pronunciation-mora-line';
      moraSpan.appendChild(line);

      container.appendChild(moraSpan);
    }

    return container;
  }

  // ── Pitches section ───────────────────────────────────────────────────────

  /**
   * pitches array entries now support:
   *   group.nasalPositions   — number[] (1-based mora indices), optional
   *   group.devoicePositions — number[] (1-based mora indices), optional
   *
   * The downstep notation span is always emitted (hidden by default via CSS,
   * shown when custom dictionary CSS targets .pronunciation-downstep-notation).
   */
  function appendPitchesSection(body, pitches, reading, showDiagram, showNumber, showText) {
    if (pitches.length === 0 || (!showDiagram && !showNumber && !showText)) return;

    const section = document.createElement('div');
    section.className = 'entry-body-section pitches-section';
    section.dataset.sectionType = 'pronunciations';

    const groupList = document.createElement('ul');
    groupList.className = 'pronunciation-group-list';
    groupList.style.listStyle = 'none';
    groupList.style.padding = '0';
    groupList.style.margin = '0';
    section.appendChild(groupList);

    for (const group of pitches) {
      const dictName       = String(group.dictName || '');
      const positions      = Array.isArray(group.pitchPositions)    ? group.pitchPositions    : [];
      const nasalPos       = Array.isArray(group.nasalPositions)     ? group.nasalPositions     : [];
      const devoicePos     = Array.isArray(group.devoicePositions)   ? group.devoicePositions   : [];
      if (positions.length === 0) continue;

      const groupContainer = document.createElement('li');
      groupContainer.className = 'pitch-entry';

      const tag = document.createElement('div');
      tag.className = 'pitch-group-tag pronunciation-group-tag-list';
      tag.textContent = dictName;
      groupContainer.appendChild(tag);

      const morae = getMorae(reading || '');

      for (const pos of positions) {
        const row = document.createElement('div');
        row.style.cssText = 'display:flex;align-items:center;gap:0.5em;flex-wrap:wrap';

        // [N] downstep notation — always emitted for CSS parity; showNumber
        // controls whether we also emit the legacy .pitch-number span.
        const downstepSpan = createDownstepNotation(pos);
        // Mirror Yomitan: place downstep notation inside a container div
        const downstepContainer = document.createElement('span');
        downstepContainer.className = 'pronunciation-downstep-notation-container';
        downstepContainer.appendChild(downstepSpan);
        row.appendChild(downstepContainer);

        // Legacy numeric badge (shown when showNumber is true)
        if (showNumber) {
          const num = document.createElement('span');
          num.className = 'pitch-number';
          num.textContent = `[${pos}]`;
          row.appendChild(num);
        }

        if (showDiagram && morae.length > 0) {
          row.appendChild(createPitchDiagram(morae, pos));
        }

        if (showText && morae.length > 0) {
          row.appendChild(createPitchTextLine(morae, pos, nasalPos, devoicePos));
        }

        groupContainer.appendChild(row);
      }

      groupList.appendChild(groupContainer);
    }

    if (section.childElementCount > 0) {
      body.appendChild(section);
    }
  }

  function renderEntry(result, mediaMap, showFrequencyHarmonic, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText, groupTerms) {
    const existingSet = Array.isArray(existingExpressions) ? existingExpressions : [];
    const article = document.createElement('article');
    article.className = 'entry';
    article.dataset.index = String(result.index || 0);
    
    // Add data-dictionary attribute for scoped CSS
    const dictName = result.term && result.term.glossaries && result.term.glossaries[0] && result.term.glossaries[0].dictName;
    if (dictName) {
      article.dataset.dictionary = dictName;
    }

    const body = document.createElement('div');
    body.className = 'entry-body';
    article.appendChild(body);

    const expression = (result.term && result.term.expression) || result.matched || '';
    const reading = result.term && result.term.reading ? result.term.reading : '';

    const headSection = document.createElement('div');
    headSection.className = 'entry-body-section entry-headword-row';
    const termTags = result.term ? result.term.termTags : '';
    headSection.appendChild(createHeadwordNode(expression, reading, termTags));

    if (ankiEnabled) {
      // Anki add button
      const ankiBtn = document.createElement('button');
      // Check if expression is already in Anki (from payload)
      const isAlreadyAdded = existingSet.includes(expression);
      ankiBtn.className = isAlreadyAdded ? 'anki-add-btn anki-added' : 'anki-add-btn';
      ankiBtn.textContent = isAlreadyAdded ? '✓' : '+';
      ankiBtn.title = isAlreadyAdded ? 'Already in Anki' : 'Add to Anki';
      ankiBtn.setAttribute('data-index', String(result.index || 0));
      ankiBtn.setAttribute('data-expression', expression);
      ankiBtn.setAttribute('data-glossary', '-1');
      ankiBtn.onclick = (e) => {
        e.stopPropagation();
        if (typeof AnkiBridge !== 'undefined') {
          const entryIdx = ankiBtn.getAttribute('data-index');
          const selectedDict = _selectedDictionaries[entryIdx] || '';
          // Fallback to live selection if stored one is empty
          const selection = _lastSelection || window.getSelection().toString();
          AnkiBridge.addToAnki(entryIdx, '-1', selectedDict, selection);
        }
      };
      headSection.appendChild(ankiBtn);
    }
    
    if (_wordAudioEnabled) {
      headSection.appendChild(createAudioButton(expression, reading));
    }

    body.appendChild(headSection);

    const frequencies = result.term && Array.isArray(result.term.frequencies) ? result.term.frequencies : [];
    appendFrequenciesSection(body, frequencies, showFrequencyHarmonic);

    const rules = result.term && result.term.rules ? result.term.rules : '';
    const process = Array.isArray(result.process) ? result.process.join(' -> ') : '';
    appendInflectionSection(body, rules, process);

    const pitches = result.term && Array.isArray(result.term.pitches) ? result.term.pitches : [];
    appendPitchesSection(body, pitches, reading, showPitchDiagram, showPitchNumber, showPitchText);

    const glossaries = (result.term && Array.isArray(result.term.glossaries)) ? result.term.glossaries : [];
    appendDefinitionsSection(body, glossaries, mediaMap, groupTerms);

    return article;
  }

  function renderSplitEntries(result, mediaMap, showFrequencyHarmonic, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText) {
    const glossaries = (result.term && Array.isArray(result.term.glossaries)) ? result.term.glossaries : [];
    if (glossaries.length === 0) {
      return [renderEntry(result, mediaMap, showFrequencyHarmonic, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText, false)];
    }

    return glossaries.map((gloss) => {
      const splitResult = Object.assign({}, result, {
        term: Object.assign({}, result.term, {
          glossaries: [gloss]
        })
      });
      return renderEntry(splitResult, mediaMap, showFrequencyHarmonic, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText, false);
    });
  }

  function renderHeader(text) {
    if (!text) return;
    const container = document.getElementById('entries');
    if (!container) return;
    const existing = container.querySelector('.popup-header');
    if (existing) {
      existing.textContent = text;
      return;
    }
    const header = document.createElement('div');
    header.className = 'popup-header';
    header.textContent = text;
    container.insertBefore(header, container.firstChild);
  }

  function render(payload) {
    console.log('[DictionaryRenderJS] render called, payload type:', typeof payload, 'keys:', payload ? Object.keys(payload).join(',') : 'null');
    
    if (!payload || typeof payload !== 'object') {
      console.error('[DictionaryRenderJS] render: invalid payload:', payload);
      return;
    }
    
    const started = performance.now();
    const root = document.documentElement;
    root.setAttribute('data-theme', payload.isDark ? 'dark' : 'light');
    _wordAudioEnabled = payload.wordAudioEnabled !== false;

    debugLog('render.start', {
      isDark: !!payload.isDark,
      styles: Array.isArray(payload.styles) ? payload.styles.length : 0,
      results: Array.isArray(payload.results) ? payload.results.length : 0
    });

    const styleNode = document.getElementById('dictionary-styles');
    if (styleNode) {
      const stylesPayload = Array.isArray(payload.styles) ? payload.styles : [];

      // Create a cache key based on dict names and CSS lengths
      const stylesKey = stylesPayload
        .map(s => `${s.dictName}:${(s.styles || '').length}`)
        .join('|');

      if (styleNode.dataset.cacheKey !== stylesKey) {
        const cssText = buildScopedDictionaryCss(stylesPayload);
        styleNode.textContent = cssText;
        styleNode.dataset.cacheKey = stylesKey;
        debugLog('render.stylesApplied', {styleTextLength: cssText.length});
      } else {
        debugLog('render.stylesCached', {cacheKey: stylesKey});
      }
    }

    const container = document.getElementById('entries');
    if (!container) return;
    container.textContent = '';

    // Re-attach tab bar (it was cleared)
    const tabs = Array.isArray(payload.tabs) ? payload.tabs : [];
    if (tabs.length > 1) {
      renderTabBar(tabs, payload.recursiveNavMode);
      // insertBefore because textContent='' removed it
      if (_tabsEl) container.insertBefore(_tabsEl, container.firstChild);
    }
    const mediaMap = payload.mediaDataUris || {};
    const results = Array.isArray(payload.results) ? payload.results : [];
    const dictionaryOrder = Array.isArray(payload.dictionaryOrder) ? payload.dictionaryOrder : [];

    if (dictionaryOrder.length > 0) {
      const priorityMap = new Map();
      dictionaryOrder.forEach((name, i) => priorityMap.set(name, i));
      const getPriority = (name) => (priorityMap.has(name) ? priorityMap.get(name) : 999);

      // 1. Sort results (stable sort primarily by matched length, tie-break by dict priority)
      results.sort((a, b) => {
        const lenA = (a.matched || '').length;
        const lenB = (b.matched || '').length;
        if (lenA !== lenB) return lenB - lenA;

        const prioA = getPriority(a.term?.glossaries?.[0]?.dictName);
        const prioB = getPriority(b.term?.glossaries?.[0]?.dictName);
        return prioA - prioB;
      });

      // 2. Sort nested arrays within each result
      for (const result of results) {
        if (result.term) {
          if (Array.isArray(result.term.glossaries)) {
            result.term.glossaries.sort((a, b) => getPriority(a.dictName) - getPriority(b.dictName));
          }
          if (Array.isArray(result.term.frequencies)) {
            result.term.frequencies.sort((a, b) => getPriority(a.dictName) - getPriority(b.dictName));
          }
          if (Array.isArray(result.term.pitches)) {
            result.term.pitches.sort((a, b) => getPriority(a.dictName) - getPriority(b.dictName));
          }
        }
      }
    }

    const existingExpressions = Array.isArray(payload.existingExpressions) ? payload.existingExpressions : [];
    const groupTerms = payload.groupTerms !== false;

    if (results.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'hoshi-empty';
      empty.textContent = payload.placeholder || '';
      container.appendChild(empty);
    } else {
      const fragment = document.createDocumentFragment();
      for (const result of results) {
        const diagram = payload.showPitchDiagram !== false;
        const number = payload.showPitchNumber !== false;
        const text = payload.showPitchText !== false;
        if (groupTerms) {
          fragment.appendChild(renderEntry(result, mediaMap, payload.showFrequencyHarmonic, existingExpressions, payload.ankiEnabled, diagram, number, text, groupTerms));
        } else {
          const articles = renderSplitEntries(result, mediaMap, payload.showFrequencyHarmonic, existingExpressions, payload.ankiEnabled, diagram, number, text);
          articles.forEach(a => fragment.appendChild(a));
        }
      }
      container.appendChild(fragment);

      if (payload.wordAudioAutoplay) {
        const firstBtn = container.querySelector('.word-audio-btn');
        if (firstBtn) firstBtn.click();
      }
    }

    const elapsed = Math.round(performance.now() - started);
    console.log('[DictionaryRenderJS] render_ms=', elapsed, 'results=', results.length);
    
    // Ensure recursive lookup tap listener is installed if enabled
    if (_lookupEnabled) {
      installTapListener();
    }
  }

  function clear() {
    const container = document.getElementById('entries');
    if (container) container.textContent = '';
    _tabsEl = null;
    _tabs = [];
    const styleNode = document.getElementById('dictionary-styles');
    if (styleNode) {
      styleNode.textContent = '';
      delete styleNode.dataset.cacheKey;
    }
  }

  window.DictionaryRenderer = {
    render,
    renderHeader,
    clear,

    renderFromBase64(base64) {
      const json = decodeBase64Utf8(base64);
      const payload = JSON.parse(json);
      render(payload);
    },

    renderFromBridge() {
      try {
        const json = PayloadBridge.getJson();
        if (!json) return;
        const payload = JSON.parse(json);
        render(payload);
      } catch (e) {
        console.error('[DictionaryRenderJS] renderFromBridge error:', e.message);
      }
    },

    updateTabs(tabsJson) {
      try {
        const tabs = JSON.parse(tabsJson);
        renderTabBar(tabs);
      } catch (e) {
        console.error('[DictionaryRenderJS] updateTabs error:', e.message);
      }
    },

    setRecursiveLookupEnabled(enabled) {
      _lookupEnabled = enabled;
      if (enabled) installTapListener();
    },

    onAudioResults: (id, results) => { /* overriden by UI */ }
  };
})();

