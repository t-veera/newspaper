/* The First Light: renders an edition JSON object into template.html.
 *
 *   window.renderEdition(data) -> Promise<report>
 *
 * All text is inserted with textContent (never innerHTML). Sections without data are
 * hidden. After fonts and images are ready it runs the masthead auto-fit and the page-1
 * news auto-fill (both from the reference design), measures the layout, and hands a
 * report to NewspaperBridge.onReady(json) when running inside the Android WebView.
 */
(function () {
  'use strict';

  var MM = 96 / 25.4;               // CSS px per mm
  var PAGE_PAD_MM = 9;              // .page vertical padding
  var NEWS_MIN_PT = 8.2, NEWS_MAX_PT = 10.5, NEWS_STEP_PT = 0.1;
  var LEADING_MIN = 1.34, LEADING_MAX = 1.42;
  var TITLE_MAX_PT = 60, TITLE_MIN_PT = 20;
  var MAX_PAGES = 6;
  var MIN_STORY_WORDS = 50;
  var ICONS = { sun: 1, part: 1, cloud: 1, rain: 1, moon: 1 };
  var IMAGE_URI = /^data:image\/(png|jpeg|gif|webp|svg\+xml);base64,[A-Za-z0-9+\/=]+$/;
  var DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
    'August', 'September', 'October', 'November', 'December'];
  var SVG_NS = 'http://www.w3.org/2000/svg';

  var warnings = [];

  /* ---------- DOM helpers ---------- */

  function $(sel) { return document.querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  function el(tag, cls, children) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    append(e, children);
    return e;
  }
  function append(parent, children) {
    if (children == null) return parent;
    if (!Array.isArray(children)) children = [children];
    children.forEach(function (c) {
      if (c == null || c === false) return;
      parent.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    });
    return parent;
  }
  function svg(tag, attrs, children) {
    var e = document.createElementNS(SVG_NS, tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    (children || []).forEach(function (c) { e.appendChild(c); });
    return e;
  }
  function icon(name) {
    if (!ICONS[name]) name = 'cloud';
    return svg('svg', { 'class': 'ico' }, [svg('use', { href: '#i-' + name })]);
  }
  function fill(id, children) {
    var e = document.getElementById(id);
    e.textContent = '';
    append(e, children);
    return e;
  }
  function show(e, visible) { e.hidden = !visible; return visible; }

  function has(a) { return Array.isArray(a) && a.length > 0; }
  function str(v) { return v == null || v === '' ? null : String(v); }
  function num(v) { return typeof v === 'number' && isFinite(v) ? v : null; }
  function dash(v) { return v == null ? '—' : String(v); }
  function grouped(n) { return n == null ? '—' : String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
  function deg(n) { return n == null ? '—' : Math.round(n) + '°'; }

  function roman(n) {
    var v = [1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1];
    var s = ['M', 'CM', 'D', 'CD', 'C', 'XC', 'L', 'XL', 'X', 'IX', 'V', 'IV', 'I'];
    var out = '';
    for (var i = 0; i < v.length; i++) while (n >= v[i]) { out += s[i]; n -= v[i]; }
    return out;
  }
  function longDate(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
    if (!m) return str(iso) || '';
    var d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
    return DAYS[d.getUTCDay()] + ', ' + d.getUTCDate() + ' ' + MONTHS[d.getUTCMonth()] + ' ' + d.getUTCFullYear();
  }
  function safeImage(uri, what) {
    if (uri == null) return null;
    if (IMAGE_URI.test(uri)) return uri;
    warnings.push(what + ' image ignored: not a base64 image data URI');
    return null;
  }

  /* ---------- page 1 ---------- */

  function renderMasthead(m, footer) {
    m = m || {};
    var name = str(m.name) || '';
    fill('title', name);
    fill('mini-title', name);
    document.title = name;
    var vol = num(m.volume), no = num(m.number);
    fill('volno', [vol != null ? 'Vol. ' + roman(vol) : null, vol != null && no != null ? ', ' : null,
      no != null ? 'No. ' + no : null]);
    $$('.js-date').forEach(function (e) { e.textContent = longDate(m.date); });
    fill('place', str(m.place));
    $$('.js-printed').forEach(function (e) { e.textContent = str(m.printedAt) ? 'Printed ' + m.printedAt : ''; });
    footer = footer || {};
    fill('footer-public', str(footer.publicNote));
    $$('.js-private').forEach(function (e) { e.textContent = str(footer.privateNote) || ''; });
  }

  function moonIcon(phase) {
    // phase: 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. Lit part is filled.
    var p = num(phase);
    p = p == null ? 0.5 : ((p % 1) + 1) % 1;
    var rx = (9 * Math.abs(Math.cos(2 * Math.PI * p))).toFixed(2);
    var waxing = p <= 0.5;
    var limb = waxing ? 'M12 3A9 9 0 0 1 12 21' : 'M12 3A9 9 0 0 0 12 21';
    var bulgesRight = waxing ? p < 0.25 : p < 0.75;
    var terminator = 'A' + rx + ' 9 0 0 ' + (bulgesRight ? 0 : 1) + ' 12 3z';
    return svg('svg', { 'class': 'ico', viewBox: '0 0 24 24' }, [
      svg('circle', { cx: 12, cy: 12, r: 9, fill: 'none', stroke: 'currentColor', 'stroke-width': 1.5 }),
      svg('path', { d: limb + terminator, fill: 'currentColor' })
    ]);
  }

  function renderWeather(w) {
    var earW = document.getElementById('ear-weather');
    var earM = document.getElementById('ear-moon');
    var strip = document.getElementById('wstrip');
    if (!w) {
      fill('ear-weather', [el('b', null, '—'), 'No weather']);
      fill('ear-moon', [el('b', null, '—'), 'No moon data']);
      show(strip, false);
      return;
    }
    fill('ear-weather', [icon(w.icon), el('b', null, w.tempC == null ? '—' : Math.round(w.tempC) + '°C'),
      str(w.condition), el('br'), 'High ' + deg(w.highC) + ' · Low ' + deg(w.lowC)]);

    var moon = w.moon;
    if (moon) {
      fill('ear-moon', [moonIcon(moon.phase), el('b', null, dash(str(moon.phaseName))),
        num(moon.illuminationPct) != null ? Math.round(moon.illuminationPct) + '% lit' : null, el('br'),
        str(moon.moonrise) ? 'Moonrise ' + moon.moonrise : null]);
    } else {
      fill('ear-moon', [el('b', null, '—'), 'No moon data']);
    }
    earW.hidden = earM.hidden = false;

    var items = (w.hourly || []).map(function (h) {
      return el('span', null, [el('b', null, str(h.hour)), icon(h.icon), deg(h.tempC),
        num(h.precipPct) != null ? ' ' + Math.round(h.precipPct) + '%' : null]);
    });
    var sun = [str(w.sunrise) ? 'Rise ' + w.sunrise : null, str(w.sunset) ? 'Set ' + w.sunset : null,
      num(w.uvIndex) != null ? 'UV ' + w.uvIndex : null].filter(Boolean).join(' · ');
    if (sun) items.push(el('span', null, sun));
    fill('wstrip', items);
    show(strip, items.length > 0);
  }

  function renderScience(s) {
    var a = document.getElementById('science');
    a.textContent = '';
    if (!s || (!str(s.headline) && !has(s.paragraphs))) { show(a, false); return; }
    show(a, true);
    if (str(s.headline)) append(a, el('h1', 'big', s.headline));
    if (str(s.deck)) append(a, el('div', 'deck', s.deck));
    var cols = el('div', 'cols3');
    (s.paragraphs || []).forEach(function (t, i) { append(cols, el('p', i === 0 ? 'dropcap' : null, t)); });
    var qr = safeImage(s.qrDataUri, 'QR');
    if (qr) {
      var img = el('img', 'qr');
      img.src = qr;
      img.alt = 'QR code to source';
      append(cols, el('div', 'qr-end', img));
    }
    append(a, cols);
  }

  function renderNewsColumn(id, label, items) {
    var col = document.getElementById(id);
    col.textContent = '';
    items = (items || []).filter(function (n) { return n && (str(n.headline) || str(n.text)); });
    if (!items.length) { show(col, false); return false; }
    show(col, true);
    var twin = el('div', 'twin' + (items.length === 1 ? ' single' : ''), items.slice(0, 2).map(function (n) {
      return el('article', null, [str(n.headline) ? el('h2', null, n.headline) : null,
        str(n.source) ? el('div', 'by', n.source) : null, str(n.text) ? el('p', null, n.text) : null]);
    }));
    if (items.length > 2) warnings.push(label + ': only the first 2 stories fit; ' + (items.length - 2) + ' dropped');
    append(col, [el('div', 'kicker', label), twin]);
    return true;
  }

  function renderNews(n) {
    n = n || {};
    var a = renderNewsColumn('news-india', 'INDIA', n.india);
    var b = renderNewsColumn('news-world', 'WORLD', n.world);
    var box = document.getElementById('news');
    box.classList.toggle('single', a !== b);
    // The news block stays in the flow (it is the page-1 filler) but is blanked when empty.
    box.style.visibility = a || b ? '' : 'hidden';
  }

  /* ---------- page 2 ---------- */

  function renderPersonalRow(d) {
    var sched = (d.schedule || []).filter(Boolean);
    var tasks = (d.tasks || []).filter(Boolean);
    var bdays = (d.birthdays || []).filter(Boolean);
    var renew = (d.renewals || []).filter(Boolean);

    var s1 = fill('schedule', has(sched) ? [el('div', 'kicker', "TODAY'S SCHEDULE"), el('ul', 'plain', sched.map(function (e) {
      return el('li', null, [el('span', 'time', str(e.time) || ''), str(e.title)]);
    }))] : null);
    var s2 = fill('tasks', has(tasks) ? [el('div', 'kicker', 'TO DO'), el('ul', 'plain', tasks.map(function (t) {
      return el('li', null, [el('span', 'box'), str(t.text)]);
    }))] : null);
    var s3 = fill('dates', [
      bdays.map(function (b) {
        return el('div', 'birthday', [el('div', 'big', 'Happy Birthday, ' + (str(b.name) || '') + '!'),
          str(b.whenLabel) ? el('div', 'when', b.whenLabel) : null]);
      }),
      has(renew) ? el('div', 'kicker', 'RENEWALS') : null,
      has(renew) ? el('ul', 'plain', renew.map(function (r) {
        return el('li', null, [el('span', 'tag', (str(r.whenLabel) || '') + ':'), ' ' + (str(r.text) || '')]);
      })) : null
    ].reduce(function (a, b) { return a.concat(b); }, []));

    var visible = [show(s1, has(sched)), show(s2, has(tasks)), show(s3, has(bdays) || has(renew))]
      .filter(Boolean).length;
    var row = document.getElementById('row3');
    row.classList.remove('cols-1', 'cols-2');
    if (visible < 3 && visible > 0) row.classList.add('cols-' + visible);
    show(row, visible > 0);
  }

  function chartFrame(children) {
    var base = [svg('line', { x1: 2, y1: 44, x2: 118, y2: 44, stroke: '#1a1a1a', 'stroke-width': '.5' })];
    var labels = [['2', '00'], ['56', '12'], ['108', '24h']].map(function (l) {
      var t = svg('text', { x: l[0], y: 53, 'font-size': 6, 'font-family': 'serif' });
      t.textContent = l[1];
      return t;
    });
    return svg('svg', { 'class': 'chart', viewBox: '0 0 120 56' }, base.concat(children, labels));
  }
  function noData() {
    var t = svg('text', { x: 60, y: 28, 'font-size': 8, 'text-anchor': 'middle', 'font-family': 'serif', 'class': 'nodata' });
    t.textContent = '—';
    return t;
  }

  function hrChart(values) {
    // Fixed 50-100 bpm scale (widened if needed), baseline y=44, top y=4; points every 2 h.
    var vs = (values || []).slice(0, 12).map(num);
    var known = vs.filter(function (v) { return v != null; });
    if (!known.length) return chartFrame([noData()]);
    var lo = Math.min(50, Math.floor(Math.min.apply(null, known) / 10) * 10);
    var hi = Math.max(100, Math.ceil(Math.max.apply(null, known) / 10) * 10);
    var k = 40 / (hi - lo);
    var pts = [];
    vs.forEach(function (v, i) {
      if (v == null) return;
      pts.push([(4 + i * 112 / 11).toFixed(1), (44 - (v - lo) * k).toFixed(1)]);
    });
    var shapes = [svg('polyline', { points: pts.map(function (p) { return p.join(','); }).join(' '),
      fill: 'none', stroke: '#1a1a1a', 'stroke-width': 1.2 })];
    pts.forEach(function (p) { shapes.push(svg('circle', { cx: p[0], cy: p[1], r: 1.3, fill: '#1a1a1a' })); });
    return chartFrame(shapes);
  }

  function stressChart(values) {
    // Stress 0-100 mapped to 40 units of height; dark bars for medium or higher (> 50).
    var vs = (values || []).slice(0, 12).map(num);
    if (!vs.some(function (v) { return v != null; })) return chartFrame([noData()]);
    var bars = [];
    vs.forEach(function (v, i) {
      if (v == null) return;
      var h = Math.max(0, Math.min(100, v)) * 0.4;
      bars.push(svg('rect', { x: (5 + i * 9.6).toFixed(1), y: (44 - h).toFixed(1), width: 6, height: h.toFixed(1),
        fill: v > 50 ? '#1a1a1a' : '#8a8780' }));
    });
    return chartFrame(bars);
  }

  function renderHealth(h) {
    // Health is always shown; a failed source renders dashes rather than hiding the box.
    var meta = h ? [str(h.source), str(h.dateLabel)].filter(Boolean).join(', ') : '';
    h = h || {};
    function stat(value, label) { return el('div', 'stat', [el('b', null, value), el('span', null, label)]); }
    fill('health', [
      el('div', 'kicker', ["YESTERDAY'S BODY", meta ? ' · ' : null, meta ? el('i', null, meta) : null]),
      el('div', 'health', [
        el('div', 'stats', [
          stat(dash(num(h.restingHr)), 'Resting HR (bpm)'),
          stat(dash(num(h.avgHr)), 'Average HR (bpm)'),
          stat(dash(num(h.avgStress)), 'Average stress'),
          stat(dash(num(h.peakStress)), 'Peak stress' + (str(h.peakStressTime) ? ', ' + h.peakStressTime : '')),
          stat(grouped(num(h.totalKcal)), 'Total calories (kcal)'),
          stat(grouped(num(h.activeKcal)), 'Active calories (kcal)')
        ]),
        el('div', null, [hrChart(h.hrTwoHourly), el('div', 'cap', 'Heart rate, 2-hour averages')]),
        el('div', null, [stressChart(h.stressTwoHourly), el('div', 'cap', 'Stress, 2-hour averages (dark = medium or higher)')])
      ])
    ]);
  }

  function renderMessages(list) {
    list = (list || []).filter(function (m) { return m && (str(m.text) || str(m.sender)); });
    var box = document.getElementById('messages');
    box.textContent = '';
    if (!show(box, list.length > 0)) return;
    var order = [], groups = {};
    list.forEach(function (m) {
      var app = str(m.app) || 'Other';
      if (!groups[app]) { groups[app] = []; order.push(app); }
      groups[app].push(m);
    });
    var summary = list.length + ' unread · ' + order.map(function (a) { return a + ' ' + groups[a].length; }).join(', ');
    append(box, [el('div', 'kicker', ['MESSAGES ', el('i', null, summary)]),
      el('div', 'msgs', order.map(function (app) {
        var g = el('div', 'group', [el('div', 'msg-app', [app + ' ', el('span', null, groups[app].length + ' unread')])]
          .concat(groups[app].map(function (m) {
            return el('div', 'msg', [el('b', null, str(m.sender) || ''), ' ', el('i', null, str(m.time) || ''),
              el('br'), str(m.text)]);
          })));
        g.setAttribute('data-app', app);
        return g;
      }))]);
  }

  function renderSports(lines) {
    lines = (lines || []).filter(function (l) { return l && str(l.text); });
    var box = document.getElementById('sports');
    box.textContent = '';
    if (!show(box, lines.length > 0)) return;
    append(box, [el('div', 'kicker', 'SPORTS'), el('div', 'list', lines.map(function (l) {
      return el('div', 'row', [el('b', null, str(l.sport) || ''), ' · ' + l.text]);
    }))]);
  }

  function renderComic(c) {
    var box = document.getElementById('comic');
    box.textContent = '';
    if (!show(box, !!c)) return;
    var uri = safeImage(c.imageDataUri, 'Comic');
    var frame = el('div', 'comic' + (uri ? ' has-image' : ''));
    if (uri) {
      var img = el('img');
      img.src = uri;
      img.alt = str(c.altText) || 'Comic strip';
      frame.appendChild(img);
    } else {
      frame.textContent = str(c.altText) || '';
    }
    append(box, [el('div', 'kicker', 'THE FUNNIES' + (str(c.title) ? ' · ' + c.title : '')), frame]);
  }

  /* ---------- fit, fill, measure ---------- */

  // Reference behaviour: the masthead title takes the widest size that fits between the ears.
  function fitTitle() {
    var size = TITLE_MAX_PT;
    $$('.title').forEach(function (t) {
      var sp = t.firstElementChild;
      size = TITLE_MAX_PT;
      sp.style.fontSize = size + 'pt';
      while (sp.offsetWidth > t.clientWidth - 6 && size > TITLE_MIN_PT) { size -= 1; sp.style.fontSize = size + 'pt'; }
      if (sp.offsetWidth > t.clientWidth - 6) warnings.push('Masthead title does not fit even at ' + TITLE_MIN_PT + 'pt');
    });
    return size;
  }

  function overflows(n) { return n.scrollHeight > n.clientHeight + 1; }

  // Reference behaviour: news text grows from 8.2pt until the block reaches the page bottom
  // (max 10.5pt). Then, as a final touch, leading is opened up slightly (1.34 -> at most 1.42)
  // to close the remaining gap of less than one line, so the block ends at the padding line.
  function fillNews() {
    var n = document.getElementById('news');
    var ps = $$('p', n);
    if (!ps.length) return { size: null, leading: null };
    function setSize(v) { ps.forEach(function (p) { p.style.fontSize = v + 'pt'; }); }
    function setLeading(v) { ps.forEach(function (p) { p.style.lineHeight = String(v); }); }
    var size = NEWS_MIN_PT;
    setSize(size);
    while (size < NEWS_MAX_PT - 1e-6) {
      var next = Math.round((size + NEWS_STEP_PT) * 10) / 10;
      setSize(next);
      if (overflows(n)) { setSize(size); break; }
      size = next;
    }
    var lo = LEADING_MIN, hi = LEADING_MAX;
    setLeading(hi);
    if (overflows(n)) {
      for (var i = 0; i < 10; i++) {
        var mid = (lo + hi) / 2;
        setLeading(mid);
        if (overflows(n)) hi = mid; else lo = mid;
      }
      setLeading(lo);
    } else {
      lo = hi;
    }
    return { size: size, leading: Math.round(lo * 1000) / 1000 };
  }

  /* ---------- extra pages for long message lists ---------- */

  function pageBottomLimit(p) { return p.getBoundingClientRect().bottom - PAGE_PAD_MM * MM; }

  function overflowsPage(p) {
    var limit = pageBottomLimit(p);
    return $$(':scope > *:not(.footer-note)', p).some(function (c) {
      return !c.hidden && c.getBoundingClientRect().bottom > limit + 0.5;
    });
  }

  function continuationPage(n, withMessages) {
    var page = el('div', 'page continuation');
    page.id = 'page-' + n;
    var title = el('div', 'mini-title', document.getElementById('mini-title').textContent);
    var dateline = el('div', 'dateline', [el('span', null, 'Personal Edition'),
      el('span', 'js-date', $$('.js-date')[0].textContent), el('span', 'js-pageno')]);
    var band = withMessages === false ? null
      : el('div', 'band', [el('div', 'kicker', ['MESSAGES ', el('i', null, 'continued')]), el('div', 'msgs')]);
    var foot = el('div', 'footer-note', [el('span', 'js-private', $$('.js-private')[0].textContent),
      el('span', 'js-printed', $$('.js-printed')[0].textContent)]);
    append(page, [title, dateline, band, foot]);
    document.getElementById('edition').appendChild(page);
    return page;
  }

  /** Moves the last message of [from] to the top of [to], keeping app group headers. */
  function moveLastMessage(from, to) {
    var group = from.lastElementChild;
    while (group && !group.querySelector('.msg')) { var empty = group; group = group.previousElementSibling; empty.remove(); }
    if (!group) return false;
    var msg = $$('.msg', group).pop();
    var app = group.getAttribute('data-app');
    var target = to.firstElementChild;
    if (!target || target.getAttribute('data-app') !== app) {
      target = el('div', 'group', el('div', 'msg-app', [app + ' ', el('span', null, 'continued')]));
      target.setAttribute('data-app', app);
      to.insertBefore(target, to.firstElementChild);
    }
    target.insertBefore(msg, target.children[1] || null);
    if (!group.querySelector('.msg')) group.remove();
    return true;
  }

  // Page 2 keeps its layout. When it overflows, messages flow onto pages 3, 4, ... as one block, and
  // sports and the comic move after the last message, so the comic always closes the paper.
  function paginateMessages() {
    var page2 = document.getElementById('page-2');
    if (!overflowsPage(page2)) return finishPages();
    var tail = ['sports', 'comic'].map(function (id) { return document.getElementById(id); })
      .filter(function (e) { return e && !e.hidden; });
    tail.forEach(function (e) { e.remove(); });

    var pages = 2, source = page2, msgs = page2.querySelector('#messages .msgs');
    while (msgs && overflowsPage(source)) {
      var next = continuationPage(++pages, true);
      var to = next.querySelector('.msgs');
      var moved = 0;
      while (overflowsPage(source) && moveLastMessage(msgs, to)) moved++;
      if (!moved) { next.remove(); pages--; break; }
      if (!msgs.querySelector('.msg')) source.querySelector('#messages').hidden = true;
      if (pages > MAX_PAGES) { warnings.push('Messages stopped at page ' + MAX_PAGES); break; }
      source = next;
      msgs = to;
    }

    if (tail.length) {
      var last = $$('.page').pop();
      tail.forEach(function (e) { last.insertBefore(e, last.querySelector('.footer-note')); });
      if (overflowsPage(last)) {
        var extra = continuationPage(++pages, false);
        tail.forEach(function (e) { extra.insertBefore(e, extra.querySelector('.footer-note')); });
      }
    }
    return finishPages();
  }

  function finishPages() {
    var all = $$('.page');
    $$('.js-pageno').forEach(function (e, i) { e.textContent = 'Page ' + (i + 2) + ' of ' + all.length; });
    all.slice(1, -1).forEach(function (p, i) {
      var right = p.querySelector('.js-printed');
      if (right) right.textContent = 'Continued on page ' + (i + 3) + ' \u2192';
    });
    return all.length;
  }

  /* ---------- fit page 1 by shortening text, never by clipping ---------- */

  function wordCount(p) { return (p.textContent.match(/\S+/g) || []).length; }

  /** Drops the last sentence of a paragraph; false when it would leave fewer than [minWords]. */
  function dropLastSentence(p, minWords) {
    var t = p.textContent.trim();
    var cut = Math.max(t.lastIndexOf('. ', t.length - 2), t.lastIndexOf('? ', t.length - 2), t.lastIndexOf('! ', t.length - 2));
    if (cut < 0) return false;
    var shorter = t.slice(0, cut + 1);
    if ((shorter.match(/\S+/g) || []).length < minWords) return false;
    p.textContent = shorter;
    return true;
  }

  /**
   * Shortens news stories (whole sentences, from the story that runs lowest) until the news block
   * fits, down to MIN_STORY_WORDS each. The science lead is never shortened (user's rule).
   */
  function shortenToFit() {
    var n = document.getElementById('news');
    if (!n || !overflows(n)) return 0;
    var trimmed = 0;
    function trimStories() {
      var arts = $$('article', n).filter(function (a) { return a.querySelector('p'); })
        .sort(function (a, b) { return b.lastElementChild.getBoundingClientRect().bottom - a.lastElementChild.getBoundingClientRect().bottom; });
      return arts.some(function (a) { return dropLastSentence($$('p', a).pop(), MIN_STORY_WORDS); });
    }
    for (var guard = 0; guard < 400 && overflows(n) && trimStories(); guard++) trimmed++;
    return trimmed;
  }

  function measure() {
    var pages = $$('.page').map(function (p, i) {
      var r = p.getBoundingClientRect();
      var limit = r.bottom - PAGE_PAD_MM * MM, worst = 0;
      $$(':scope > *:not(.footer-note)', p).forEach(function (c) {
        if (c.hidden) return;
        worst = Math.max(worst, c.getBoundingClientRect().bottom - limit);
      });
      var wide = p.scrollWidth > p.clientWidth + 1;
      if (worst > 0.5) warnings.push('Page ' + (i + 1) + ' overflows by about ' + (worst / MM).toFixed(1) + ' mm');
      if (wide) warnings.push('Page ' + (i + 1) + ' content is wider than the page');
      return { widthMm: r.width / MM, heightMm: r.height / MM, overflowMm: Math.max(0, worst / MM) };
    });

    // Anything in the flow above page 1 shifts every printed page down and splits them.
    var first = $$('.page')[0];
    if (first && first.offsetTop > parseFloat(getComputedStyle(first).marginTop) + 1) {
      warnings.push('Content above page 1 pushes the pages down by ' +
        ((first.offsetTop - parseFloat(getComputedStyle(first).marginTop)) / MM).toFixed(1) + ' mm');
    }

    var n = document.getElementById('news'), newsGapMm = null, newsClipped = false;
    if (n && n.style.visibility !== 'hidden') {
      newsClipped = overflows(n);
      if (newsClipped) warnings.push('Page 1 news is clipped');
      var page = document.getElementById('page-1').getBoundingClientRect();
      var padLine = page.bottom - PAGE_PAD_MM * MM;
      var bottom = -Infinity;
      $$('article', n).forEach(function (a) {
        var last = a.lastElementChild;
        if (last) bottom = Math.max(bottom, last.getBoundingClientRect().bottom);
      });
      if (bottom > -Infinity) {
        newsGapMm = (padLine - bottom) / MM;
        if (!newsClipped && newsGapMm > 2) warnings.push('Page 1 news ends ' + newsGapMm.toFixed(1) + ' mm above the bottom margin');
      }
    }
    $$('.comic').forEach(function (c) {
      if (c.scrollHeight > c.clientHeight + 1) warnings.push('Comic image is clipped');
    });
    return { pages: pages, newsGapMm: newsGapMm, newsClipped: newsClipped };
  }

  function fontsReady() {
    if (!document.fonts) return Promise.resolve([]);
    var faces = ['400 12pt "UnifrakturMaguntia"', '400 12pt "Libre Caslon Text"', 'italic 400 12pt "Libre Caslon Text"',
      '700 12pt "Libre Caslon Text"', '700 12pt "Playfair Display"', '900 12pt "Playfair Display"'];
    return Promise.all(faces.map(function (f) { return document.fonts.load(f).catch(function () { return []; }); }))
      .then(function () { return document.fonts.ready; })
      .then(function () { return faces.filter(function (f) { return !document.fonts.check(f); }); });
  }

  function imagesReady() {
    return Promise.all($$('img').map(function (img) {
      if (!img.decode) return Promise.resolve();
      return img.decode().catch(function () { warnings.push('An image failed to load: ' + (img.alt || 'image')); });
    }));
  }

  function nextFrame() {
    return new Promise(function (resolve) { setTimeout(resolve, 50); });
  }

  function deliver(report) {
    var w = document.getElementById('fitwarn');
    if (w) { w.textContent = report.warnings.join(' | '); w.style.display = report.warnings.length ? 'block' : 'none'; }
    var json = JSON.stringify(report);
    if (window.NewspaperBridge && window.NewspaperBridge.onReady) window.NewspaperBridge.onReady(json);
    return report;
  }

  window.renderEdition = function (data) {
    warnings = [];
    $$('.page.continuation').forEach(function (p) { p.remove(); });
    var started = Date.now();
    return Promise.resolve().then(function () {
      data = data || {};
      renderMasthead(data.masthead, data.footer);
      renderWeather(data.weather);
      renderScience(data.science);
      renderNews(data.news);
      renderPersonalRow(data);
      renderHealth(data.health);
      renderMessages(data.messages);
      renderSports(data.sports);
      renderComic(data.comic);
      return Promise.all([fontsReady(), imagesReady()]);
    }).then(function (res) {
      res[0].forEach(function (f) { warnings.push('Font not loaded: ' + f); });
      return nextFrame();
    }).then(function () {
      var titlePt = fitTitle();
      var news = fillNews();
      var shortened = shortenToFit();
      if (shortened) news = fillNews(); // re-fill: the trim may leave room to grow again
      var pageCount = paginateMessages();
      return nextFrame().then(function () {
        var m = measure();
        return deliver({ ok: true, warnings: warnings, titlePt: titlePt, newsPt: news.size, newsLeading: news.leading,
          newsGapMm: m.newsGapMm, newsClipped: m.newsClipped, pages: m.pages, pageCount: pageCount,
          renderMs: Date.now() - started });
      });
    }).catch(function (e) {
      return deliver({ ok: false, error: String(e && e.message || e), warnings: warnings });
    });
  };
})();
