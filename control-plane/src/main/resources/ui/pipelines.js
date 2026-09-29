/*
 * The pipeline designer (ADR 0037).
 *
 * A palette of origins, processors and destinations; a canvas to place and wire them; a panel that
 * configures the selected stage from what that component declares it reads; and a preview that pulls
 * a few real records through every stage without writing anything.
 *
 * Like the mapping editor, it decides nothing about validity. Every check -- settings, wiring that
 * reaches across artifacts, whether the whole resolves -- is the server's, which asks the same
 * resolver `niem run --pipeline` uses. The one rule enforced here is the one a canvas has to enforce
 * as you draw: what may connect to what. And layout is computed, never stored (ADR 0022): a pipeline
 * file names its parts; where their boxes sit is this page's business.
 */

import * as nav from './nav.js';
import {
  el, api, post, hint, action, field, problems as problemBlock, setStatusLine, humanize,
} from './shared.js';

const SVG = 'http://www.w3.org/2000/svg';

const NODE_W = 210;
const NODE_H = 86;
// Stages sit close enough that a pipeline of three columns fits the canvas without scrolling
// sideways at an ordinary laptop width; the edge between columns still has room for its count.
const COLUMN_X = { origin: 16, mapping: 312, destination: 608 };
const VIEW_W = 834;

// Characters that fit on a stage's lines, at the canvas's monospace sizes.
const FIT = { 'pl-node-title': 17, 'pl-node-sub': 26, 'pl-node-count': 26, 'pl-node-hint': 27 };

/** Titles short enough for a stage box; the palette keeps the long names. */
const SHORT = {
  'file-drop': 'File drop', kafka: 'Kafka', sftp: 'SFTP', ftps: 'FTPS',
  ods: 'ODS', search: 'Search', graph: 'Graph', 'cch-http': 'CCH exchange',
};
const STORE = { ods: 'PostgreSQL', search: 'Elasticsearch', graph: 'Neo4j', 'cch-http': 'HTTP' };

const ICONS = {
  folder: 'M3 7h6l2 2h10v10H3z',
  stream: 'M3 8c3 0 3-3 6-3s3 3 6 3 3-3 6-3M3 14c3 0 3-3 6-3s3 3 6 3 3-3 6-3M3 20c3 0 3-3 6-3s3 3 6 3 3-3 6-3',
  server: 'M4 4h16v6H4zM4 14h16v6H4zM7 7h.01M7 17h.01',
  mapping: 'M4 6h6v4H4zM14 14h6v4h-6zM10 8h2a2 2 0 0 1 2 2v4',
  table: 'M3 5h18v14H3zM3 10h18M9 5v14',
  search: 'M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14zM16 16l5 5',
  graph: 'M6 7a2 2 0 1 0 0 .1M18 6a2 2 0 1 0 0 .1M12 18a2 2 0 1 0 0 .1M7.5 8.5l3.5 7.5M16.5 7.5L13 16M8 6.5l8-.5',
  repository: 'M5 4h14v16H5zM8 8h8M8 12h8M8 16h5',
  box: 'M4 4h16v16H4z',
};

export function initPipelines(hooks) {
  const pl = {
    loaded: false,
    palette: null,
    list: [],
    draft: emptyDraft(),
    wires: new Set(),
    selected: null,
    validation: null,
    preview: null,
    debounce: null,
    drag: null,
    notice: null,
    openedFrom: null,
    // The Mapping stage the author drilled into, and where the canvas was, so "Back to pipeline"
    // returns to exactly that.
    trip: null,
    // A newer version of this pipeline's mapping, saved while the author was in the mapping editor.
    pendingUse: null,
  };

  // ------------------------------------------------------------------------------ model

  function emptyDraft() {
    return {
      pipeline: { name: '', version: '1.0.0', description: '' },
      origin: null,
      mapping: null,
      destinations: [],
    };
  }

  const nextId = (() => { let n = 0; return (prefix) => `${prefix}-${++n}`; })();

  const originPalette = (type) => (pl.palette?.origins || []).find((o) => o.type === type);
  const destinationPalette = (kind, type) =>
    (pl.palette?.destinations || []).find((d) => d.kind === kind && d.type === type);
  const mappingEntry = (ref) =>
    (pl.palette?.processors?.[0]?.mappings || []).find((m) => m.ref === ref);
  const sourceEntry = (sourceId, instance) =>
    (pl.palette?.sources || []).find((s) => s.name === `${sourceId}/${instance}`);

  function originSourceId() {
    return pl.draft.origin?.sourceId || null;
  }

  /** The stage id the server attributes problems to. */
  function stageId(node) {
    if (node.kind === 'origin') return 'origin';
    if (node.kind === 'mapping') return 'mapping';
    const d = node.destination;
    return `${d.kind}:${d.mode === 'new' ? `${d.name}@${d.version}` : d.ref}`;
  }

  /** Stages as the canvas draws them, with layout computed from the model. */
  function nodes() {
    const list = [];
    if (pl.draft.origin) {
      list.push({ id: 'origin', kind: 'origin', x: COLUMN_X.origin, y: 150 });
    }
    if (pl.draft.mapping !== null) {
      list.push({ id: 'mapping', kind: 'mapping', x: COLUMN_X.mapping, y: 150 });
    }
    const n = pl.draft.destinations.length;
    const top = Math.max(24, 150 + NODE_H / 2 - (n * (NODE_H + 22)) / 2);
    pl.draft.destinations.forEach((destination, i) => {
      list.push({
        id: destination.id, kind: 'destination', destination,
        x: COLUMN_X.destination, y: top + i * (NODE_H + 22),
      });
    });
    return list;
  }

  /** The draft as the server reads it: only what is wired is part of the pipeline. */
  function draftForServer() {
    const d = pl.draft;
    const wired = d.destinations.filter((x) => pl.wires.has(`mapping>${x.id}`));
    return {
      pipeline: { ...d.pipeline },
      origin: d.origin ? { ...d.origin, settings: { ...(d.origin.settings || {}) } } : {},
      mapping: d.mapping || null,
      destinations: wired.map((x) => (x.mode === 'new'
        ? { kind: x.kind, mode: 'new', name: x.name, version: x.version, type: x.type, settings: { ...x.settings } }
        : { kind: x.kind, mode: 'existing', ref: x.ref })),
    };
  }

  function unwired() {
    const problems = [];
    if (pl.draft.origin && pl.draft.mapping !== null && !pl.wires.has('origin>mapping')) {
      problems.push('The origin is not connected to the mapping.');
    }
    pl.draft.destinations.forEach((x) => {
      if (!pl.wires.has(`mapping>${x.id}`)) problems.push(`${destinationLabel(x)} is not connected; it will not receive anything.`);
    });
    return problems;
  }

  // --------------------------------------------------------------------------- wiring

  /**
   * Whether an output may connect to an input -- the one rule the canvas owns. The rest (does this
   * exchange send this source?) is also said here, before a line is drawn, because refusing a wire
   * after it has been drawn teaches nobody anything.
   */
  function canConnect(fromId, toId) {
    if (fromId === 'origin' && toId === 'mapping') {
      const mapping = mappingEntry(pl.draft.mapping);
      const source = originSourceId();
      if (mapping && source && mapping.sourceId !== source) {
        return `This mapping is written against source "${mapping.sourceId}", and the origin is "${source}". `
          + 'Records would map cleanly and describe the wrong feed.';
      }
      return null;
    }
    if (fromId === 'mapping' && toId.startsWith('dest-')) {
      const destination = pl.draft.destinations.find((x) => x.id === toId);
      if (destination?.kind === 'exchange' && destination.mode !== 'new') {
        const exchange = (pl.palette?.exchanges || []).find((e) => e.ref === destination.ref || e.name === destination.ref);
        const source = originSourceId();
        if (exchange && source && exchange.sourceId !== source) {
          return `Exchange "${exchange.name}" sends source "${exchange.sourceId}", not "${source}" (ADR 0034).`;
        }
      }
      return null;
    }
    if (fromId === 'origin') return 'An origin feeds the mapping. Every record is gated and mapped before it goes anywhere.';
    if (toId === 'origin') return 'Nothing flows into an origin.';
    return 'The mapping is the only thing that feeds a destination.';
  }

  function connect(fromId, toId) {
    const refusal = canConnect(fromId, toId);
    if (refusal) {
      status('invalid', refusal);
      return false;
    }
    pl.wires.add(`${fromId}>${toId}`);
    changed();
    return true;
  }

  // ---------------------------------------------------------------------------- palette

  function renderPalette() {
    const nav = el('pl-palette');
    nav.replaceChildren();
    const group = (title, hint, items) => {
      const section = document.createElement('section');
      section.className = 'pl-group';
      const h = document.createElement('h2');
      h.textContent = title;
      const p = document.createElement('p');
      p.className = 'detail';
      p.textContent = hint;
      const ul = document.createElement('ul');
      items.forEach((item) => ul.append(item));
      section.append(h, p, ul);
      nav.append(section);
    };

    group('Origins', 'Where records come from.',
      (pl.palette.origins || []).map((o) => paletteItem(o.label, o.summary, o.icon, { kind: 'origin', type: o.type }, 'origin')));
    group('Processors', 'What they become. Gates and identity come with the mapping.',
      [paletteItem('Mapping', pl.palette.processors?.[0]?.summary || '', 'mapping', { kind: 'mapping' }, 'processor')]);
    group('Destinations', 'Where the canonical records go.',
      (pl.palette.destinations || []).map((d) => paletteItem(d.label, d.summary, d.icon, { kind: d.kind, type: d.type }, 'destination')));
  }

  function paletteItem(label, summary, icon, payload, kind) {
    const li = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `pl-item pl-item--${kind}`;
    button.draggable = true;
    button.title = `${summary}\n\nDrag onto the canvas, or press to add.`;
    button.append(iconSvg(icon, 18));
    const text = document.createElement('span');
    text.innerHTML = '';
    const strong = document.createElement('strong');
    strong.textContent = label;
    const small = document.createElement('small');
    small.textContent = summary;
    text.append(strong, small);
    button.append(text);
    button.addEventListener('dragstart', (event) => {
      event.dataTransfer.setData('application/x-niem-stage', JSON.stringify(payload));
      event.dataTransfer.effectAllowed = 'copy';
    });
    // Click-to-add: the same thing without a mouse.
    button.addEventListener('click', () => addStage(payload));
    li.append(button);
    return li;
  }

  function addStage(payload) {
    if (payload.kind === 'origin') {
      if (pl.draft.origin && !confirmReplace('origin')) return;
      pl.draft.origin = {
        mode: 'new', type: payload.type, sourceId: originSourceId() || '', instance: '', settings: defaults(originPalette(payload.type)),
      };
      pl.wires.delete('origin>mapping');
      select('origin');
    } else if (payload.kind === 'mapping') {
      if (pl.draft.mapping !== null && !confirmReplace('mapping')) return;
      const candidates = (pl.palette.processors?.[0]?.mappings || []).filter((m) => !originSourceId() || m.sourceId === originSourceId());
      pl.draft.mapping = candidates[0]?.ref || '';
      if (pl.draft.origin && !canConnect('origin', 'mapping')) pl.wires.add('origin>mapping');
      select('mapping');
    } else {
      const id = nextId('dest');
      const destination = payload.kind === 'exchange'
        ? { id, kind: 'exchange', mode: 'existing', type: payload.type, ref: firstExchange(payload.type) }
        : { id, kind: 'projection', mode: 'existing', type: payload.type, ref: firstProjection(payload.type) || '' };
      if (payload.kind === 'projection' && !destination.ref) {
        Object.assign(destination, { mode: 'new', name: '', version: '1.0.0', settings: defaults(destinationPalette('projection', payload.type)) });
      }
      pl.draft.destinations.push(destination);
      if (pl.draft.mapping !== null && !canConnect('mapping', id)) pl.wires.add(`mapping>${id}`);
      select(id);
    }
    changed();
  }

  function confirmReplace(what) {
    // No window.confirm: the replacement is announced, and the previous stage is one undo away in
    // the saved file. A pipeline has one origin and one mapping.
    status('idle', `Replaced the ${what}: a pipeline has exactly one.`);
    return true;
  }

  function firstExchange(type) {
    const source = originSourceId();
    const list = (pl.palette.exchanges || []).filter((e) => e.type === type && (!source || e.sourceId === source));
    return list[0]?.ref || (pl.palette.exchanges || []).find((e) => e.type === type)?.ref || '';
  }

  function firstProjection(type) {
    return (pl.palette.projections || []).find((p) => p.type === type)?.ref || '';
  }

  function defaults(entry) {
    return {};
  }

  // ----------------------------------------------------------------------------- canvas

  function render() {
    const frame = el('pl-canvas');
    const list = nodes();
    const height = Math.max(360, ...list.map((n) => n.y + NODE_H + 40));
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('class', 'pl-svg');
    svg.setAttribute('viewBox', `0 0 ${VIEW_W} ${height}`);
    svg.setAttribute('role', 'group');
    svg.setAttribute('aria-label', 'Pipeline stages');

    const defs = document.createElementNS(SVG, 'defs');
    defs.innerHTML = '<marker id="pl-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" class="pl-arrowhead"/></marker>';
    svg.append(defs);

    if (!list.length && !pl.list.length) {
      const empty = document.createElementNS(SVG, 'text');
      empty.setAttribute('x', VIEW_W / 2);
      empty.setAttribute('y', 170);
      empty.setAttribute('class', 'pl-empty');
      empty.setAttribute('text-anchor', 'middle');
      empty.textContent = 'Drop an origin here — or use “Add a data source” above.';
      svg.append(empty);
    }

    // Column captions: the order a record moves in, said once.
    [['ORIGIN', COLUMN_X.origin], ['PROCESSOR', COLUMN_X.mapping], ['DESTINATIONS', COLUMN_X.destination]].forEach(([t, x]) => {
      const caption = document.createElementNS(SVG, 'text');
      caption.setAttribute('x', x);
      caption.setAttribute('y', 18);
      caption.setAttribute('class', 'pl-caption');
      caption.textContent = t;
      svg.append(caption);
    });

    const byId = Object.fromEntries(list.map((n) => [n.id, n]));
    pl.wires.forEach((wire) => {
      const [from, to] = wire.split('>');
      if (byId[from] && byId[to]) svg.append(edge(byId[from], byId[to]));
    });
    list.forEach((node) => svg.append(nodeGroup(node)));

    frame.replaceChildren(svg);
    if (!list.length && pl.list.length) frame.append(openList());
    frame.ondragover = (event) => {
      if ([...event.dataTransfer.types].includes('application/x-niem-stage')) {
        event.preventDefault();
        frame.classList.add('is-drop');
      }
    };
    frame.ondragleave = () => frame.classList.remove('is-drop');
    frame.ondrop = (event) => {
      frame.classList.remove('is-drop');
      const raw = event.dataTransfer.getData('application/x-niem-stage');
      if (!raw) return;
      event.preventDefault();
      addStage(JSON.parse(raw));
    };
  }

  /** On an empty canvas: the pipelines there already are, so the first thing to do is obvious. */
  function openList() {
    const box = document.createElement('div');
    box.className = 'pl-open-list';
    const h = document.createElement('h3');
    h.textContent = 'Open a pipeline';
    box.append(h);
    pl.list.filter((p) => p.loadable).forEach((p) => {
      box.append(action(`${p.name}@${p.version}`, () => openPipeline(p.file), 'ghost'));
    });
    box.append(hint('Or start a new one: drop an origin from the palette here, or use “Add a data source” above.'));
    return box;
  }

  function point(svg, event) {
    const p = svg.createSVGPoint();
    p.x = event.clientX;
    p.y = event.clientY;
    return p.matrixTransform(svg.getScreenCTM().inverse());
  }

  function edge(from, to) {
    const g = document.createElementNS(SVG, 'g');
    g.setAttribute('class', 'pl-edge');
    const x1 = from.x + NODE_W; const y1 = from.y + NODE_H / 2;
    const x2 = to.x; const y2 = to.y + NODE_H / 2;
    const path = document.createElementNS(SVG, 'path');
    const mid = (x1 + x2) / 2;
    path.setAttribute('d', `M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2 - 2},${y2}`);
    path.setAttribute('marker-end', 'url(#pl-arrow)');
    g.append(path);
    const label = edgeLabel(from, to);
    if (label) {
      const text = document.createElementNS(SVG, 'text');
      text.setAttribute('x', mid);
      text.setAttribute('y', (y1 + y2) / 2 - 6);
      text.setAttribute('text-anchor', 'middle');
      text.setAttribute('class', 'pl-edge-label');
      text.textContent = label;
      g.append(text);
    }
    // Removing a wire: double-click it. Also offered in the panel, for keyboards.
    g.addEventListener('dblclick', () => { pl.wires.delete(`${from.id}>${to.id}`); changed(); });
    const title = document.createElementNS(SVG, 'title');
    title.textContent = 'Double-click to disconnect';
    g.append(title);
    return g;
  }

  function edgeLabel(from, to) {
    const stages = pl.preview?.stages;
    if (!stages) return '';
    if (from.id === 'origin') return `${stages.gate?.out ?? stages.origin?.out ?? 0} ▸`;
    if (from.id === 'mapping') {
      const d = stages.destinations?.[stageId(to)];
      if (!d) return '';
      return d.unresolved ? 'supplied by deployment' : `${d.in} ${d.unit}`;
    }
    return '';
  }

  function nodeGroup(node) {
    const g = document.createElementNS(SVG, 'g');
    const problems = problemsFor(node);
    g.setAttribute('class', `pl-node pl-node--${node.kind}`
      + (pl.selected === node.id ? ' is-selected' : '')
      + (problems.length ? ' has-problems' : ''));
    g.setAttribute('transform', `translate(${node.x},${node.y})`);
    g.setAttribute('tabindex', '0');
    g.setAttribute('role', 'button');
    g.setAttribute('aria-label', `${nodeTitle(node)}${problems.length ? `, ${problems.length} problem(s)` : ''}`);

    const rect = document.createElementNS(SVG, 'rect');
    rect.setAttribute('width', NODE_W);
    rect.setAttribute('height', NODE_H);
    rect.setAttribute('rx', 10);
    g.append(rect);

    const icon = iconSvg(nodeIcon(node), 18, true);
    icon.setAttribute('x', 12);
    icon.setAttribute('y', 12);
    g.append(icon);

    g.append(svgText(40, 26, nodeTitle(node), 'pl-node-title'));
    g.append(svgText(14, 50, nodeSubtitle(node), 'pl-node-sub'));
    const count = nodeCount(node);
    g.append(svgText(14, 72, count || nodeHint(node), count ? 'pl-node-count' : 'pl-node-hint'));

    if (problems.length) {
      const badge = document.createElementNS(SVG, 'g');
      badge.setAttribute('class', 'pl-badge');
      badge.setAttribute('transform', `translate(${NODE_W - 14},14)`);
      badge.innerHTML = `<circle r="10"></circle><text text-anchor="middle" y="4">${problems.length}</text>`;
      g.append(badge);
    }

    // Ports. Output on the right of an origin and the mapping; input on the left of the mapping
    // and every destination.
    if (node.kind !== 'origin') g.append(port(0, NODE_H / 2, 'in', node));
    if (node.kind !== 'destination') g.append(port(NODE_W, NODE_H / 2, 'out', node));

    g.addEventListener('click', () => select(node.id));
    g.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(node.id); }
      if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); removeStage(node.id); }
    });
    if (node.kind === 'mapping') {
      g.addEventListener('dblclick', () => openMappingEditor());
    }
    return g;
  }

  function port(x, y, direction, node) {
    const c = document.createElementNS(SVG, 'circle');
    c.setAttribute('cx', x);
    c.setAttribute('cy', y);
    c.setAttribute('r', 7);
    c.setAttribute('class', `pl-port pl-port--${direction}`);
    c.dataset.node = node.id;
    c.dataset.direction = direction;
    if (direction === 'out') {
      c.addEventListener('pointerdown', (event) => startWire(event, node));
    }
    return c;
  }

  /** Drag from an output port; a line follows the pointer and lands on an input port, or nowhere. */
  function startWire(event, from) {
    event.stopPropagation();
    event.preventDefault();
    const svg = event.target.ownerSVGElement;
    const line = document.createElementNS(SVG, 'path');
    line.setAttribute('class', 'pl-wire-draft');
    svg.append(line);
    const x1 = from.x + NODE_W; const y1 = from.y + NODE_H / 2;
    const move = (e) => {
      const p = point(svg, e);
      line.setAttribute('d', `M${x1},${y1} L${p.x},${p.y}`);
      svg.querySelectorAll('.pl-port--in').forEach((portEl) => {
        const target = portEl.dataset.node;
        portEl.classList.toggle('is-target', document.elementFromPoint(e.clientX, e.clientY) === portEl);
        portEl.classList.toggle('is-refused', !!canConnect(from.id, target));
      });
    };
    const up = (e) => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      line.remove();
      const target = document.elementFromPoint(e.clientX, e.clientY);
      if (target?.classList?.contains('pl-port--in')) connect(from.id, target.dataset.node);
      else render();
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  function svgText(x, y, text, cls) {
    const t = document.createElementNS(SVG, 'text');
    t.setAttribute('x', x);
    t.setAttribute('y', y);
    t.setAttribute('class', cls);
    const fit = FIT[cls] || 30;
    t.textContent = text.length > fit ? `${text.slice(0, fit - 1)}…` : text;
    if (text.length > fit) {
      const title = document.createElementNS(SVG, 'title');
      title.textContent = text;
      t.append(title);
    }
    return t;
  }

  function iconSvg(name, size, inside) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('width', size);
    svg.setAttribute('height', size);
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'pl-icon');
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS(SVG, 'path');
    path.setAttribute('d', ICONS[name] || ICONS.box);
    svg.append(path);
    return svg;
  }

  function nodeIcon(node) {
    if (node.kind === 'origin') return originPalette(pl.draft.origin?.type)?.icon
      || (pl.draft.origin?.mode === 'existing' ? 'stream' : 'box');
    if (node.kind === 'mapping') return 'mapping';
    return destinationPalette(node.destination.kind, node.destination.type)?.icon || 'box';
  }

  function nodeTitle(node) {
    if (node.kind === 'origin') {
      const o = pl.draft.origin;
      const type = o.mode === 'existing' ? sourceEntry(o.sourceId, o.instance)?.type : o.type;
      return SHORT[type] || originPalette(type)?.label || type || 'Origin';
    }
    if (node.kind === 'mapping') return 'Mapping';
    return SHORT[node.destination.type] || destinationLabel(node.destination);
  }

  function destinationLabel(d) {
    return destinationPalette(d.kind, d.type)?.label || (d.kind === 'exchange' ? 'Exchange' : 'Projection');
  }

  function nodeSubtitle(node) {
    if (node.kind === 'origin') {
      const o = pl.draft.origin;
      return o.sourceId ? `${o.sourceId} / ${o.instance || '(new instance)'}` : 'not configured';
    }
    if (node.kind === 'mapping') return pl.draft.mapping || 'choose a mapping';
    const d = node.destination;
    return d.mode === 'new' ? `${d.name || '(new)'}@${d.version || '?'} · new` : (d.ref || 'choose a definition');
  }

  function nodeHint(node) {
    if (node.kind === 'mapping') return 'gates · steps · identity';
    if (node.kind === 'origin') return pl.draft.origin?.mode === 'new' ? 'new source definition' : 'existing source definition';
    const store = STORE[node.destination.type];
    const what = node.destination.kind === 'exchange' ? 'submits documents' : 'every record';
    return store ? `${store} · ${what}` : what;
  }

  function nodeCount(node) {
    const s = pl.preview?.stages;
    if (!s) return '';
    if (node.kind === 'origin') return s.origin ? `${s.origin.out} read` : '';
    if (node.kind === 'mapping') {
      if (!s.mapping) return '';
      const held = (s.gate?.held || 0) + (s.mapping?.held || 0);
      return `${s.mapping.in} in ▸ ${s.mapping.out} canonical${held ? ` · ${held} held` : ''}`;
    }
    const d = s.destinations?.[stageId(node)];
    if (!d) return '';
    return d.unresolved ? 'would receive (deployment-supplied)' : `would receive ${d.in} ${d.unit}`;
  }

  function problemsFor(node) {
    const all = pl.validation?.problems || {};
    if (node.kind === 'destination') {
      const d = node.destination;
      // A new projection's settings are checked under its bare name, its resolution under
      // name@version; both are this stage.
      const keys = d.mode === 'new' ? [`projection:${d.name}`, `projection:${d.name}@${d.version}`] : [`${d.kind}:${d.ref}`];
      return keys.flatMap((k) => all[k] || []);
    }
    return all[stageId(node)] || [];
  }

  function removeStage(id) {
    if (id === 'origin') pl.draft.origin = null;
    else if (id === 'mapping') pl.draft.mapping = null;
    else pl.draft.destinations = pl.draft.destinations.filter((x) => x.id !== id);
    [...pl.wires].filter((w) => w.split('>').includes(id)).forEach((w) => pl.wires.delete(w));
    pl.selected = null;
    changed();
  }

  const KIND_WORD = { origin: 'Origin', mapping: 'Processor', destination: 'Destination' };

  // ------------------------------------------------------------------------- the panel

  function select(id) {
    pl.selected = id;
    render();
    renderConfig();
    nav.go({ stage: id }, { replace: true });
    hooks.changed?.();
  }

  function renderConfig() {
    const aside = el('pl-config');
    aside.replaceChildren();
    const node = nodes().find((n) => n.id === pl.selected);
    if (!node) {
      renderPipelinePanel(aside);
      return;
    }
    const head = document.createElement('div');
    head.className = 'pl-config-head';
    const h = document.createElement('h2');
    h.className = 'panel-title';
    h.textContent = `${KIND_WORD[node.kind]} · ${nodeTitle(node)}`;
    const remove = action('Remove', () => removeStage(node.id), 'ghost');
    head.append(h, remove);
    aside.append(head);
    problemList(aside, problemsFor(node));

    if (node.kind === 'origin') renderOrigin(aside);
    else if (node.kind === 'mapping') renderMapping(aside);
    else renderDestination(aside, node.destination);

    renderWiring(aside, node);
  }

  function renderPipelinePanel(aside) {
    const h = document.createElement('h2');
    h.textContent = 'Pipeline';
    h.className = 'panel-title';
    aside.append(h);
    aside.append(hint('Select a stage to configure it. A pipeline is saved as a versioned file that names '
      + 'its origin, mapping and destinations; `niem run --pipeline` runs exactly what you save.'));
    aside.append(field('Description', input(pl.draft.pipeline.description, (v) => { pl.draft.pipeline.description = v; changed(); })));
    const problems = [...(pl.validation?.problems?.pipeline || []), ...(pl.validation?.problems?.destinations || []), ...unwired()];
    problemList(aside, problems);
    const notes = pl.validation?.notes || [];
    if (notes.length) {
      const h3 = document.createElement('h3');
      h3.textContent = 'Notes';
      const ul = document.createElement('ul');
      ul.className = 'pl-notes';
      notes.forEach((n) => { const li = document.createElement('li'); li.textContent = n; ul.append(li); });
      aside.append(h3, ul);
    }
    if (pl.validation?.yaml) {
      const details = document.createElement('details');
      details.className = 'pl-yaml';
      const summary = document.createElement('summary');
      summary.textContent = 'The file Save will write';
      const pre = document.createElement('pre');
      pre.textContent = pl.validation.yaml;
      details.append(summary, pre);
      aside.append(details);
    }
  }

  function renderOrigin(aside) {
    const o = pl.draft.origin;
    if (o.mode === 'existing') {
      const entry = sourceEntry(o.sourceId, o.instance);
      aside.append(field('Source definition', select_(
        (pl.palette.sources || []).map((s) => [s.name, `${s.name} · ${s.type}${s.deployment ? ' (deployment)' : ''}`]),
        `${o.sourceId}/${o.instance}`,
        (v) => { const [sid, inst] = v.split('/'); o.sourceId = sid; o.instance = inst; changed(); })));
      if (entry) {
        aside.append(readOnlySettings(entry.settings));
        aside.append(action('Edit as a new transport', () => {
          Object.assign(o, {
            mode: 'new', type: entry.type, instance: `${o.instance}-2`,
            settings: Object.fromEntries(Object.entries(entry.settings || {}).filter(([, v]) => v !== '••••••')),
          });
          changed();
          renderConfig();
        }, 'ghost'));
        aside.append(hint('A running pipeline\'s source is never altered under it: edits become a new instance.'));
      }
    } else {
      const entry = originPalette(o.type);
      aside.append(field('Source id', input(o.sourceId, (v) => { o.sourceId = v; changed(); }, 'leon-so-cad', sourceIdList())));
      aside.append(field('Instance', input(o.instance, (v) => { o.instance = v; changed(); }, `${o.sourceId || 'source'}-${o.type}-1`)));
      aside.append(field('Freshness SLA', input(o.freshnessSla || '', (v) => { o.freshnessSla = v; changed(); }, 'PT15M')));
      aside.append(settingsForm(entry?.settings || [], o.settings, changed));
    }
    const test = action('Test connection', () => testConnection(test.parentElement), 'primary');
    const wrap = document.createElement('div');
    wrap.className = 'pl-test';
    wrap.append(test);
    aside.append(wrap);
  }

  async function testConnection(container) {
    const result = container.querySelector('.pl-test-result') || document.createElement('p');
    result.className = 'pl-test-result';
    result.textContent = 'Testing…';
    container.append(result);
    try {
      const response = await api('/api/source/test', post({ origin: draftForServer().origin }));
      result.classList.toggle('is-ok', !!response.healthy);
      result.classList.toggle('is-bad', !response.healthy);
      const detail = String(response.detail || '');
      const said = detail && detail.toLowerCase() !== 'reachable' && detail.toLowerCase() !== String(response.state).toLowerCase();
      result.textContent = `${response.healthy ? '✓ Reachable' : `✗ ${response.state}`}${said ? ` — ${detail}` : ''}`
        + (response.consumerGroup ? ` (probed as ${response.consumerGroup}; the live group is untouched)` : '');
    } catch (error) {
      result.classList.add('is-bad');
      result.textContent = error.message;
    }
    return result;
  }

  function renderMapping(aside) {
    const all = pl.palette.processors?.[0]?.mappings || [];
    const source = originSourceId();
    const options = all.map((m) => [m.ref, `${m.ref} · ${m.sourceId}${source && m.sourceId !== source ? ' — other source' : ''}`]);
    aside.append(field('Mapping', select_(options, pl.draft.mapping, (v) => { pl.draft.mapping = v; changed(); renderConfig(); })));
    const entry = mappingEntry(pl.draft.mapping);
    if (entry) {
      const facts = document.createElement('dl');
      facts.className = 'pl-facts';
      facts.innerHTML = '';
      [['Source', entry.sourceId], ['Hops', entry.hops], ['Produces', entry.produces.join(', ')]].forEach(([k, v]) => {
        const dt = document.createElement('dt'); dt.textContent = k;
        const dd = document.createElement('dd'); dd.textContent = v;
        facts.append(dt, dd);
      });
      aside.append(facts);
      aside.append(hint('Every hop is gated by its contract on the way in and out, and identities are resolved inside it. '
        + 'A record a contract refuses is held, never dropped.'));
      aside.append(action('Open in the mapping editor', () => openMappingEditor(), 'primary'));
      aside.append(hint('Or double-click the stage. The mapping opens with this pipeline named above it, '
        + 'and Preview there reads the same records as the pipeline preview.'));
    }
    if (pl.pendingUse && pl.pendingUse !== pl.draft.mapping) {
      aside.append(useOffer());
    }
    if (source && !all.some((m) => m.sourceId === source)) {
      aside.append(hint(`No mapping in this module reads "${source}" yet. Write one in the Flow view, saved `
        + `with source: ${source}; it appears here once saved.`));
    }
  }

  /** A newer version of the mapping, saved on the trip into it, offered back to this pipeline. */
  function useOffer() {
    const box = document.createElement('div');
    box.className = 'pl-offer';
    const version = pl.pendingUse.split('@')[1];
    box.append(hint(`${pl.pendingUse} was saved while you were in the mapping. This pipeline still uses `
      + `${pl.draft.mapping}.`));
    box.append(action(`Use v${version} in this pipeline`, () => {
      const was = pl.draft.mapping;
      pl.draft.mapping = pl.pendingUse;
      pl.pendingUse = null;
      changed();
      renderConfig();
      status('idle', `Changed: the Mapping stage now uses ${pl.draft.mapping} instead of ${was}. `
        + `Save as v${pl.draft.pipeline.version} to keep it.`);
    }, 'primary'));
    return box;
  }

  /** Drills into the Mapping stage: the mapping editor, with this pipeline kept as its context. */
  function openMappingEditor() {
    const entry = mappingEntry(pl.draft.mapping);
    if (!entry) return;
    const frame = el('pl-canvas');
    pl.trip = { selected: 'mapping', scrollLeft: frame.scrollLeft, scrollTop: frame.scrollTop,
      windowY: window.scrollY };
    hooks.openMapping(mappingContext(entry));
  }

  function mappingContext(entry = mappingEntry(pl.draft.mapping)) {
    if (!entry) return null;
    const origin = pl.draft.origin ? draftForServer().origin : null;
    return {
      file: entry.file,
      label: currentLabel(),
      mappingName: entry.ref.split('@')[0],
      mappingRef: entry.ref,
      origin,
    };
  }

  function currentLabel() {
    const p = pl.draft.pipeline;
    if (!p.name && !pl.draft.origin && pl.draft.mapping === null && !pl.draft.destinations.length) return null;
    return pl.openedFrom || `${p.name || 'new pipeline'}@${p.version || '?'} (not saved)`;
  }

  function renderDestination(aside, d) {
    const paletteEntry = destinationPalette(d.kind, d.type);
    if (d.kind === 'exchange') {
      const list = (pl.palette.exchanges || []).filter((e) => e.type === d.type);
      aside.append(field('Exchange', select_(list.map((e) => [e.ref, `${e.ref} · sends ${e.sourceId}`]), d.ref,
        (v) => { d.ref = v; changed(); renderConfig(); })));
      const entry = list.find((e) => e.ref === d.ref);
      if (entry) {
        aside.append(readOnlySettings(entry.settings));
        aside.append(hint(`Assembles ${entry.root} documents. An exchange's assembly is authored in its own file (ADR 0034).`));
      }
      return;
    }
    const existing = (pl.palette.projections || []).filter((p) => p.type === d.type);
    const choices = [...existing.map((p) => [p.ref, `${p.ref}${p.deployment ? ' (deployment)' : ''}`]), ['__new__', 'New definition…']];
    aside.append(field('Definition', select_(choices, d.mode === 'new' ? '__new__' : d.ref, (v) => {
      if (v === '__new__') Object.assign(d, { mode: 'new', name: d.name || '', version: '1.0.0', settings: d.settings || {} });
      else Object.assign(d, { mode: 'existing', ref: v });
      changed();
      renderConfig();
    })));
    if (d.mode === 'new') {
      aside.append(field('Name', input(d.name, (v) => { d.name = v; changed(); }, `laptop-${d.type}`)));
      aside.append(field('Version', input(d.version, (v) => { d.version = v; changed(); }, '1.0.0')));
      aside.append(settingsForm(paletteEntry?.settings || [], d.settings, changed));
      aside.append(hint('Saved as its own versioned definition under projections/. Nothing connects to the store '
        + 'from here: opening a projection claims it for a tenant, which a form must never do.'));
    } else {
      const entry = existing.find((p) => p.ref === d.ref);
      if (entry) aside.append(readOnlySettings(entry.settings));
      else if (d.ref) aside.append(hint('Not in this module: a deployment supplies it (niem run --artifacts).'));
    }
  }

  function renderWiring(aside, node) {
    const h3 = document.createElement('h3');
    h3.textContent = 'Connections';
    aside.append(h3);
    const ul = document.createElement('ul');
    ul.className = 'pl-wires';
    const targets = node.kind === 'origin' ? (pl.draft.mapping !== null ? ['mapping'] : [])
      : node.kind === 'mapping' ? pl.draft.destinations.map((x) => x.id) : [];
    targets.forEach((target) => {
      const key = `${node.id}>${target}`;
      const li = document.createElement('li');
      const label = document.createElement('span');
      label.textContent = `→ ${target === 'mapping' ? 'Mapping' : destinationLabel(pl.draft.destinations.find((x) => x.id === target))}`;
      const on = pl.wires.has(key);
      const refusal = canConnect(node.id, target);
      li.append(label, action(on ? 'Disconnect' : 'Connect', () => {
        if (on) { pl.wires.delete(key); changed(); } else connect(node.id, target);
        renderConfig();
      }, 'ghost inline'));
      if (refusal && !on) li.append(hint(refusal));
      ul.append(li);
    });
    if (node.kind === 'destination') {
      const li = document.createElement('li');
      li.textContent = pl.wires.has(`mapping>${node.id}`) ? '← Mapping' : 'Not connected: select the Mapping to connect it.';
      ul.append(li);
    }
    aside.append(ul);
  }

  // ------------------------------------------------------------------- generated forms

  /**
   * A settings form built from what the component declares it reads. Secrets that the component
   * would read literally get no input at all: the deployment supplies them (ADR 0015).
   */
  function settingsForm(declared, values, onChange) {
    const form = document.createElement('div');
    form.className = 'pl-form';
    declared.forEach((s) => {
      let control;
      if (s.sensitivity === 'secret_value') {
        control = document.createElement('p');
        control.className = 'pl-secret';
        control.textContent = 'Supplied where the pipeline is deployed — never written into this file.';
      } else if (s.kind === 'choice' || s.kind === 'boolean') {
        const options = s.kind === 'boolean' ? ['true', 'false'] : s.options;
        control = select_([['', s.default ? `(default: ${s.default})` : '—'], ...options.map((o) => [o, o])],
          values[s.key] || '', (v) => { setOrDelete(values, s.key, v); onChange(); });
      } else {
        control = input(values[s.key] || '', (v) => { setOrDelete(values, s.key, v); onChange(); },
          s.sensitivity === 'env_var_name' ? 'ENVIRONMENT_VARIABLE_NAME' : (s.default || ''));
        if (s.kind === 'integer') control.inputMode = 'numeric';
      }
      const wrapper = field(`${s.label}${s.required ? ' *' : ''}`, control);
      wrapper.dataset.key = s.key;
      if (s.sensitivity === 'env_var_name') wrapper.append(hint('The name of the environment variable holding the secret.'));
      else if (s.description) wrapper.append(hint(s.description));
      form.append(wrapper);
    });
    if (!declared.length) form.append(hint('This component does not describe its settings; its own checks run on save.'));
    return form;
  }

  function setOrDelete(values, key, value) {
    if (value === '' || value === undefined) delete values[key];
    else values[key] = value;
  }

  function readOnlySettings(settings) {
    const dl = document.createElement('dl');
    dl.className = 'pl-facts';
    Object.entries(settings || {}).forEach(([k, v]) => {
      const dt = document.createElement('dt'); dt.textContent = k;
      const dd = document.createElement('dd'); dd.textContent = v;
      dl.append(dt, dd);
    });
    return dl;
  }

  function input(value, commit, placeholder, list) {
    const box = document.createElement('input');
    box.type = 'text';
    box.spellcheck = false;
    box.value = value ?? '';
    if (placeholder) box.placeholder = placeholder;
    if (list) box.setAttribute('list', list);
    box.addEventListener('input', () => commit(box.value.trim()));
    return box;
  }

  function select_(options, current, commit) {
    const sel = document.createElement('select');
    options.forEach(([value, label]) => {
      const opt = document.createElement('option');
      opt.value = value;
      opt.textContent = label;
      if (value === current) opt.selected = true;
      sel.append(opt);
    });
    sel.addEventListener('change', () => commit(sel.value));
    return sel;
  }

  function sourceIdList() {
    let list = document.getElementById('pl-source-ids');
    if (!list) {
      list = document.createElement('datalist');
      list.id = 'pl-source-ids';
      document.body.append(list);
    }
    const ids = new Set((pl.palette.processors?.[0]?.mappings || []).map((m) => m.sourceId));
    list.replaceChildren(...[...ids].map((id) => { const o = document.createElement('option'); o.value = id; return o; }));
    return 'pl-source-ids';
  }

  function problemList(container, problems) {
    if (!problems.length) return;
    container.append(problemBlock(problems));
  }

  // --------------------------------------------------------------------- server round trips

  /**
   * Drops any wire that no longer holds. A wire drawn while the origin and mapping agreed does not
   * stay valid when either is changed afterwards, and a canvas that kept drawing it would show a
   * pipeline that cannot run as though it could.
   */
  function pruneWires() {
    [...pl.wires].forEach((wire) => {
      const [from, to] = wire.split('>');
      const refusal = canConnect(from, to);
      if (refusal) {
        pl.wires.delete(wire);
        pl.notice = `Disconnected: ${refusal}`;
      }
    });
  }

  function changed() {
    pruneWires();
    pl.preview = null;
    el('pl-drawer').hidden = true;
    syncToolbar();
    render();
    clearTimeout(pl.debounce);
    pl.debounce = setTimeout(validate, 450);
  }

  async function validate() {
    if (!pl.draft.origin && pl.draft.mapping === null && !pl.draft.destinations.length) {
      pl.validation = null;
      renderConfig();
      return null;
    }
    try {
      pl.validation = await api('/api/pipeline/validate', post(draftForServer()));
      const count = Object.values(pl.validation.problems || {}).reduce((n, list) => n + list.length, 0) + unwired().length;
      const notice = pl.notice ? `${pl.notice} · ` : '';
      pl.notice = null;
      status(count || notice ? 'invalid' : 'valid', notice + (count ? `${count} problem(s) — marked on the stages` : 'Valid: this pipeline resolves as niem run would resolve it.'));
    } catch (error) {
      status('invalid', error.message);
    }
    render();
    renderConfig();
    return pl.validation;
  }

  async function preview() {
    status('idle', 'Reading a few records from the origin… nothing is written or acknowledged.');
    try {
      pl.preview = await api('/api/preview', post({ draft: draftForServer(), limit: 10 }));
    } catch (error) {
      status('invalid', error.message);
      return;
    }
    if (!pl.preview.ran) {
      status('invalid', pl.preview.why || 'Preview could not run.');
      pl.preview = null;
      return;
    }
    const s = pl.preview.stages || {};
    const held = (s.gate?.held || 0) + (s.mapping?.held || 0);
    status(held ? 'invalid' : 'valid',
      `Preview: ${pl.preview.read} read ▸ ${s.gate?.out ?? pl.preview.read} past the gate ▸ ${s.mapping?.out ?? 0} canonical records`
      + `${held ? ` · ${held} held` : ''}. Nothing was written${pl.preview.consumerGroup ? `; read as ${pl.preview.consumerGroup}` : ''}.`);
    render();
    showDrawer('origin');
  }

  function showDrawer(tab) {
    const p = pl.preview;
    el('pl-drawer').hidden = false;
    document.querySelectorAll('.pl-tab').forEach((t) => t.classList.toggle('is-current', t.dataset.tab === tab));
    const c = p.completeness;
    el('pl-completeness').textContent = c
      ? `Completeness: ${c.summary} — ${c.balanced ? 'balanced' : 'DOES NOT BALANCE'}`
      : 'Origin only: choose a mapping to see what these become.';
    const body = el('pl-drawer-body');
    body.replaceChildren();
    const samples = p.samples || {};
    if (tab === 'origin') {
      const ol = document.createElement('ol');
      ol.className = 'pl-raw';
      (samples.origin || []).forEach((line) => { const li = document.createElement('li'); li.textContent = line; ol.append(li); });
      body.append(ol);
    } else if (tab === 'mapped') {
      const table = document.createElement('table');
      table.className = 'pl-table';
      table.innerHTML = '<thead><tr><th>#</th><th>Type</th><th>Fields</th></tr></thead>';
      const tbody = document.createElement('tbody');
      (samples.mapped || []).forEach((r) => {
        const tr = document.createElement('tr');
        const fields = Object.entries(r.fields).filter(([k]) => k !== 'canonicalId').map(([k, v]) => `${k}=${v}`).join('  ·  ');
        [r.record, r.type, fields].forEach((v) => { const td = document.createElement('td'); td.textContent = v; tr.append(td); });
        tbody.append(tr);
      });
      table.append(tbody);
      body.append(table);
    } else {
      const held = samples.held || [];
      if (!held.length) body.append(hint('Nothing held back: every record passed every contract.'));
      held.forEach((h) => {
        const card = document.createElement('div');
        card.className = 'pl-held';
        const raw = document.createElement('code');
        raw.textContent = `#${h.record}  ${h.raw}`;
        const ul = document.createElement('ul');
        h.violations.forEach((v) => { const li = document.createElement('li'); li.textContent = humanize(v); ul.append(li); });
        card.append(raw, ul);
        body.append(card);
      });
    }
  }

  async function save() {
    const unconnected = unwired();
    if (unconnected.length) {
      status('invalid', unconnected[0]);
      return;
    }
    const result = await api('/api/pipeline/save', post(draftForServer()));
    pl.validation = result;
    if (!result.saved) {
      const first = Object.values(result.problems || {}).flat()[0];
      status('invalid', `Not saved: ${first || 'fix the marked stages first.'}`);
      render();
      renderConfig();
      return;
    }
    await refreshPalette();
    await loadList();
    await openPipeline(result.file);
    status('valid', `Saved ${result.written.join(', ')}. Run it: niem run --pipeline pipelines/${result.file}`);
  }

  /** A save can create source and projection definitions, which the palette lists. */
  async function refreshPalette() {
    pl.palette = await api('/api/palette');
    renderPalette();
  }

  // ------------------------------------------------------------------------ opening

  async function loadList() {
    pl.list = (await api('/api/pipelines')).pipelines;
    const sel = el('pl-open');
    sel.replaceChildren();
    const fresh = document.createElement('option');
    fresh.value = '';
    fresh.textContent = 'New pipeline';
    sel.append(fresh);
    pl.list.forEach((p) => {
      const opt = document.createElement('option');
      opt.value = p.file;
      opt.textContent = `${p.name}@${p.version}${p.loadable ? '' : ' (does not load)'}`;
      sel.append(opt);
    });
  }

  async function openPipeline(file) {
    if (!file) {
      pl.draft = emptyDraft();
      pl.wires = new Set();
      pl.selected = null;
      pl.validation = null;
      pl.openedFrom = null;
      pl.pendingUse = null;
      el('pl-open').value = '';
      changed();
      renderConfig();
      nav.go({ view: 'pipelines', pipeline: null, stage: null, mapping: null, hop: null, step: null, draft: null });
      hooks.changed?.();
      return;
    }
    const opened = await api(`/api/pipeline?file=${encodeURIComponent(file)}`);
    const d = opened.draft;
    pl.draft = {
      pipeline: d.pipeline,
      origin: d.origin,
      mapping: d.mapping,
      destinations: d.destinations.map((x) => {
        const entry = x.kind === 'exchange'
          ? (pl.palette.exchanges || []).find((e) => e.ref === x.ref || e.name === x.ref)
          : (pl.palette.projections || []).find((p) => p.ref === x.ref || p.name === x.ref);
        return { ...x, id: nextId('dest'), type: entry?.type || (x.kind === 'exchange' ? 'cch-http' : 'ods') };
      }),
    };
    // Everything a saved pipeline names is connected: that is what naming it means.
    pl.wires = new Set(['origin>mapping', ...pl.draft.destinations.map((x) => `mapping>${x.id}`)]);
    pl.openedFrom = d.pipeline.openedFrom;
    pl.selected = null;
    el('pl-open').value = file;
    pl.validation = opened.validation;
    syncToolbar();
    render();
    renderConfig();
    pl.pendingUse = null;
    status('idle', `Opened ${pl.openedFrom}. Saving writes ${d.pipeline.name}@${d.pipeline.version}; the opened version is never changed.`);
    nav.go({ view: 'pipelines', pipeline: d.pipeline.name, stage: null, mapping: null, hop: null, step: null, draft: null });
    hooks.changed?.();
  }

  function syncToolbar() {
    el('pl-name').value = pl.draft.pipeline.name;
    el('pl-version').value = pl.draft.pipeline.version;
    el('pl-save').textContent = `Save as v${pl.draft.pipeline.version || '?'}`;
  }

  function status(kind, text) {
    setStatusLine(el('pl-status'), kind, humanize(text));
  }

  // ------------------------------------------------------------------------- the wizard

  /*
   * The obvious path, in the order a person adding a source actually works: which transport, its
   * settings, can we reach it, what does it send, what reads it, where does it go, what is it called.
   * Each step writes into the same draft the canvas edits, so closing the wizard at any point leaves
   * the work on the canvas rather than discarding it.
   */
  const STEPS = ['Transport', 'Configure', 'Test', 'Sample', 'Mapping', 'Destinations', 'Save'];
  const wizard = { step: 0, sample: null, skeleton: null, away: false, templates: null };

  function openWizard() {
    pl.draft = emptyDraft();
    pl.wires = new Set();
    pl.selected = null;
    pl.validation = null;
    wizard.step = 0;
    wizard.sample = null;
    wizard.skeleton = null;
    wizard.away = false;
    // The wizard builds a new pipeline on the canvas; the one that was open is no longer what the
    // address names.
    nav.go({ pipeline: null, stage: null });
    el('pl-wizard-dialog').showModal();
    renderWizard();
  }

  function renderWizard() {
    const steps = el('pl-wizard-steps');
    steps.replaceChildren(...STEPS.map((name, i) => {
      const li = document.createElement('li');
      li.textContent = name;
      li.className = i === wizard.step ? 'is-current' : i < wizard.step ? 'is-done' : '';
      return li;
    }));
    const body = el('pl-wizard-body');
    body.replaceChildren();
    el('pl-wizard-back').disabled = wizard.step === 0;
    el('pl-wizard-next').textContent = wizard.step === STEPS.length - 1 ? 'Save pipeline' : 'Next';
    const o = pl.draft.origin;

    if (wizard.step === 0) {
      body.append(hint('How does this agency send its records?'));
      const grid = document.createElement('div');
      grid.className = 'pl-cards';
      (pl.palette.origins || []).forEach((entry) => {
        const card = document.createElement('button');
        card.type = 'button';
        card.className = `pl-card${o?.type === entry.type ? ' is-current' : ''}`;
        card.append(iconSvg(entry.icon, 22));
        const strong = document.createElement('strong'); strong.textContent = entry.label;
        const small = document.createElement('small'); small.textContent = entry.summary;
        card.append(strong, small);
        card.addEventListener('click', () => {
          pl.draft.origin = { mode: 'new', type: entry.type, sourceId: o?.sourceId || '', instance: '', settings: {} };
          renderWizard();
        });
        grid.append(card);
      });
      body.append(grid);
    } else if (wizard.step === 1) {
      const entry = originPalette(o.type);
      body.append(field('Source id *', input(o.sourceId, (v) => { o.sourceId = v; }, 'leon-so-cad', sourceIdList())));
      body.append(hint('The feed these records belong to. A mapping is written against a source id.'));
      body.append(field('Instance *', input(o.instance, (v) => { o.instance = v; }, `${o.sourceId || 'source'}-${o.type}-1`)));
      body.append(field('Freshness SLA', input(o.freshnessSla || '', (v) => { o.freshnessSla = v; }, 'PT15M')));
      body.append(settingsForm(entry?.settings || [], o.settings, () => {}));
    } else if (wizard.step === 2) {
      body.append(hint('Ask the transport whether it can reach its source. Nothing is read.'));
      const wrap = document.createElement('div');
      wrap.className = 'pl-test';
      const button = action('Test connection', () => testConnection(wrap), 'primary');
      wrap.append(button);
      body.append(wrap);
    } else if (wizard.step === 3) {
      body.append(hint('A few records exactly as the source sends them. Read on a throwaway consumer group, '
        + 'never acknowledged: the live feed is not touched.'));
      const button = action('Read a sample', async () => {
        wizard.sample = await api('/api/preview', post({ draft: { ...draftForServer(), mapping: null }, limit: 5 }));
        renderWizard();
      }, 'primary');
      body.append(button);
      if (wizard.sample) {
        if (!wizard.sample.ran) body.append(problemBox(wizard.sample.why));
        else {
          const ol = document.createElement('ol');
          ol.className = 'pl-raw';
          (wizard.sample.samples.origin || []).forEach((line) => { const li = document.createElement('li'); li.textContent = line; ol.append(li); });
          body.append(ol);
          if (wizard.sample.consumerGroup) body.append(hint(`Read as ${wizard.sample.consumerGroup}.`));
        }
      }
    } else if (wizard.step === 4) {
      const all = pl.palette.processors?.[0]?.mappings || [];
      const matching = all.filter((m) => m.sourceId === o.sourceId);
      if (!matching.length) {
        renderNewMapping(body, o, all);
      } else {
        if (!matching.some((m) => m.ref === pl.draft.mapping)) pl.draft.mapping = matching[0].ref;
        matching.forEach((m) => {
          const label = document.createElement('label');
          label.className = 'pl-choice';
          const radio = document.createElement('input');
          radio.type = 'radio';
          radio.name = 'pl-wizard-mapping';
          radio.checked = pl.draft.mapping === m.ref;
          radio.addEventListener('change', () => { pl.draft.mapping = m.ref; });
          const text = document.createElement('span');
          text.textContent = `${m.ref} — ${m.hops} hop(s), produces ${m.produces.join(', ')}`;
          label.append(radio, text);
          body.append(label);
        });
        body.append(hint('The mapping carries the contract gates and identity resolution.'));
      }
    } else if (wizard.step === 5) {
      body.append(hint('Where the canonical records go. Every projection gets every record; an exchange submits documents — one exchange per pipeline.'));
      const chosen = new Set(pl.draft.destinations.map((x) => x.ref));
      const options = [
        ...(pl.palette.projections || []).map((p) => ({ kind: 'projection', type: p.type, ref: p.ref, label: `${p.ref} · ${p.type}${p.deployment ? ' (deployment)' : ''}` })),
        ...(pl.palette.exchanges || []).filter((e) => e.sourceId === o.sourceId)
          .map((e) => ({ kind: 'exchange', type: e.type, ref: e.ref, label: `${e.ref} · submits ${e.root} documents` })),
      ];
      if (!options.length) body.append(hint('No destinations are defined yet. Add one from the palette after the wizard.'));
      options.forEach((opt) => {
        const label = document.createElement('label');
        label.className = 'pl-choice';
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = chosen.has(opt.ref);
        box.dataset.kind = opt.kind;
        box.addEventListener('change', () => {
          if (box.checked && opt.kind === 'exchange') {
            // A run submits through one exchange, so choosing one sets aside any other.
            pl.draft.destinations = pl.draft.destinations.filter((x) => x.kind !== 'exchange');
            body.querySelectorAll('input[data-kind="exchange"]').forEach((other) => { if (other !== box) other.checked = false; });
          }
          if (box.checked) pl.draft.destinations.push({ id: nextId('dest'), kind: opt.kind, mode: 'existing', type: opt.type, ref: opt.ref });
          else pl.draft.destinations = pl.draft.destinations.filter((x) => x.ref !== opt.ref);
        });
        const text = document.createElement('span');
        text.textContent = opt.label;
        label.append(box, text);
        body.append(label);
      });
    } else if (wizard.step === 6) {
      if (!pl.draft.pipeline.name) pl.draft.pipeline.name = `${o.sourceId}-${o.type}`.replace(/[^a-z0-9-]/g, '-');
      body.append(field('Pipeline name *', input(pl.draft.pipeline.name, (v) => { pl.draft.pipeline.name = v; }, 'leon-cad-live')));
      body.append(field('Version *', input(pl.draft.pipeline.version, (v) => { pl.draft.pipeline.version = v; }, '1.0.0')));
      body.append(field('Description', input(pl.draft.pipeline.description, (v) => { pl.draft.pipeline.description = v; })));
      if (wizard.problems) wizard.problems.forEach((p) => body.append(problemBox(p)));
    }
  }

  /*
   * No mapping reads this source yet. Not a dead end: start one from the columns the sample just
   * showed, following the hops of a mapping the module already has, with the module's advisor
   * proposing what it can and the contracts drafted from the shapes it saw. The mapping opens in the
   * mapping editor; saving it there brings the author straight back here with it chosen.
   */
  function renderNewMapping(body, o, all) {
    body.append(hint(`No mapping in this module reads "${o.sourceId}" yet. Start one from what it sent:`));
    const templates = [...new Map(all.map((m) => [m.ref.split('@')[0], m])).values()]
      .sort((a, b) => b.hops - a.hops);
    const template = document.createElement('select');
    templates.forEach((m) => {
      const option = document.createElement('option');
      option.value = m.ref;
      option.textContent = `${m.ref} — ${m.hops} hops, produces ${m.produces.join(', ')}`;
      template.append(option);
    });
    body.append(field('Follow the hops of', template));
    body.append(hint('The new mapping gets the same hops, identities and roles; its steps read your columns. '
      + 'A contract is drafted for each hop from the shapes sampled — nothing is saved until you save the mapping.'));

    const result = document.createElement('div');
    result.className = 'pl-skeleton';
    const start = action(wizard.skeleton ? 'Start again from the sample' : 'Start a new mapping from these columns', async () => {
      start.disabled = true;
      result.replaceChildren(hint('Reading the sample and drafting…'));
      try {
        wizard.skeleton = await api('/api/mapping/skeleton', post({ origin: draftForServer().origin, template: template.value }));
      } catch (error) {
        wizard.skeleton = { ran: false, why: error.message };
      }
      start.disabled = false;
      renderSkeleton(result);
    }, wizard.skeleton ? 'ghost' : 'primary');
    body.append(start, result);
    if (wizard.skeleton) renderSkeleton(result);
  }

  function renderSkeleton(container) {
    const k = wizard.skeleton;
    container.replaceChildren();
    if (!k.ran) {
      container.append(problemBox(k.why));
      return;
    }
    const facts = document.createElement('dl');
    facts.className = 'pl-facts';
    const fact = (term, value) => {
      const dt = document.createElement('dt'); dt.textContent = term;
      const dd = document.createElement('dd'); dd.textContent = value;
      facts.append(dt, dd);
    };
    fact('Mapping', `${k.mapping} (not saved yet)`);
    fact('Columns', `${k.columns.length} ${k.header ? 'from the header' : 'numbered — the source sent no header'}`);
    fact('Contracts drafted', k.contractsWritten.length ? k.contractsWritten.join(', ') : 'none — already present');
    fact('Steps proposed', k.seeded.length ? k.seeded.map((s) => `${s.target} (${Math.round(s.confidence * 100)}%)`).join(', ') : 'none');
    container.append(facts);

    const shapes = document.createElement('details');
    const summary = document.createElement('summary');
    summary.textContent = `Shapes sampled from ${k.sampled} record(s)`;
    const table = document.createElement('table');
    table.className = 'pl-table';
    table.innerHTML = '<thead><tr><th>Column</th><th>Shapes seen</th></tr></thead>';
    const tbody = document.createElement('tbody');
    k.columns.forEach((column) => {
      const tr = document.createElement('tr');
      const seen = Object.entries(column.shapes);
      [column.name, seen.length ? seen.map(([shape, n]) => (seen.length > 1 ? `${shape} ×${n}` : shape)).join(', ') : 'always empty']
        .forEach((text) => { const td = document.createElement('td'); td.textContent = text; tr.append(td); });
      if (seen.length > 1) tr.className = 'is-bad';
      tbody.append(tr);
    });
    table.append(tbody);
    shapes.append(summary, table);
    container.append(shapes);

    if (k.problems.length) {
      container.append(hint(`Still to do before it can be saved (${k.problems.length}):`));
      container.append(problemBlock(k.problems));
    }
    container.append(action('Open it in the mapping editor', () => {
      wizard.away = true;
      el('pl-wizard-dialog').close();
      hooks.openDraft({ yaml: k.yaml, name: k.mapping.split('@')[0], sourceId: pl.draft.origin.sourceId,
        origin: draftForServer().origin });
    }, 'primary'));
    container.append(hint('Saving it there brings you back here with it chosen.'));
  }

  function problemBox(text) {
    const p = document.createElement('p');
    p.className = 'pl-problem-box';
    p.textContent = text;
    return p;
  }

  async function wizardNext() {
    const o = pl.draft.origin;
    if (wizard.step === 0 && !o) return;
    if (wizard.step === 1 && (!o.sourceId || !o.instance)) {
      el('pl-wizard-body').prepend(problemBox('A source id and an instance are required.'));
      return;
    }
    if (wizard.step === STEPS.length - 1) {
      pl.wires = new Set(['origin>mapping', ...pl.draft.destinations.map((x) => `mapping>${x.id}`)]);
      const result = await api('/api/pipeline/save', post(draftForServer()));
      if (!result.saved) {
        wizard.problems = Object.entries(result.problems || {}).flatMap(([stage, list]) => list.map((p) => `${stage}: ${p}`));
        renderWizard();
        return;
      }
      el('pl-wizard-dialog').close();
      wizard.problems = null;
      hooks.leftMapping?.();
      await refreshPalette();
      await loadList();
      await openPipeline(result.file);
      status('valid', `Saved ${result.written.join(', ')}. Run it: niem run --pipeline pipelines/${result.file}`);
      return;
    }
    wizard.step += 1;
    renderWizard();
  }

  function closeWizard() {
    el('pl-wizard-dialog').close();
    wizard.away = false;
    hooks.leftMapping?.();
    // The work stays on the canvas: closing is not discarding.
    pl.wires = new Set([
      ...(pl.draft.origin && pl.draft.mapping ? ['origin>mapping'] : []),
      ...pl.draft.destinations.map((x) => `mapping>${x.id}`),
    ]);
    if (pl.draft.mapping === undefined) pl.draft.mapping = null;
    changed();
    renderConfig();
  }

  // --------------------------------------------------------------------------- wiring up

  el('pl-open').addEventListener('change', (event) => openPipeline(event.target.value));
  el('pl-name').addEventListener('input', (event) => { pl.draft.pipeline.name = event.target.value.trim(); changed(); });
  el('pl-version').addEventListener('input', (event) => { pl.draft.pipeline.version = event.target.value.trim(); changed(); });
  el('pl-validate').addEventListener('click', validate);
  el('pl-preview').addEventListener('click', preview);
  el('pl-save').addEventListener('click', () => save().catch((error) => status('invalid', error.message)));
  el('pl-wizard').addEventListener('click', openWizard);
  el('pl-wizard-next').addEventListener('click', () => wizardNext().catch((error) => {
    el('pl-wizard-body').prepend(problemBox(error.message));
  }));
  el('pl-wizard-back').addEventListener('click', () => { wizard.step = Math.max(0, wizard.step - 1); renderWizard(); });
  el('pl-wizard-cancel').addEventListener('click', closeWizard);
  el('pl-wizard-dialog').addEventListener('cancel', (event) => { event.preventDefault(); closeWizard(); });
  el('pl-drawer-close').addEventListener('click', () => { el('pl-drawer').hidden = true; });
  document.querySelectorAll('.pl-tab').forEach((tab) => tab.addEventListener('click', () => showDrawer(tab.dataset.tab)));

  /** Back from the mapping editor, to the stage the author drilled in from, as they left it. */
  function returnFromMapping() {
    const trip = pl.trip;
    pl.trip = null;
    if (pl.pendingUse && pl.pendingUse !== pl.draft.mapping) {
      // The preview was read under the version this pipeline still uses; saying so beats a drawer
      // of counts that describe a mapping the author has just changed.
      pl.preview = null;
      el('pl-drawer').hidden = true;
      status('idle', `${pl.pendingUse} was saved in the mapping editor. The Mapping stage offers it; `
        + 'preview again once it is in use.');
    }
    select(trip?.selected || 'mapping');
    const frame = el('pl-canvas');
    if (trip) {
      frame.scrollLeft = trip.scrollLeft;
      frame.scrollTop = trip.scrollTop;
      window.scrollTo(0, trip.windowY);
    }
    hooks.leftMapping?.();
  }

  /** Back into the wizard at its Mapping step, with a mapping saved on the way chosen. */
  function returnToWizard() {
    wizard.away = false;
    wizard.step = 4;
    if (!el('pl-wizard-dialog').open) el('pl-wizard-dialog').showModal();
    renderWizard();
  }

  /**
   * A mapping was saved in the mapping editor. Returns what the author should be told there, or
   * takes them back to the wizard when that is where the mapping was started from.
   */
  async function mappingSaved(result, { fromWizard, fromPipeline }) {
    await refreshPalette();
    if (fromWizard && pl.draft.origin?.sourceId === result.sourceId) {
      pl.draft.mapping = result.qualifiedName;
      wizard.skeleton = null;
      returnToWizard();
      hooks.leftMapping?.();
      return 'Back in the wizard with it chosen.';
    }
    const current = pl.draft.mapping ? pl.draft.mapping.split('@')[0] : null;
    if (fromPipeline && current === result.name && pl.draft.mapping !== result.qualifiedName) {
      pl.pendingUse = result.qualifiedName;
      return `Back to pipeline to use v${result.version} there.`;
    }
    return '';
  }

  async function openByName(name, stage) {
    if (!pl.loaded) await this.show();
    const current = pl.draft.pipeline?.name;
    if (current !== name || !pl.openedFrom) {
      const match = pl.list.find((p) => p.name === name || p.file === name);
      if (match) await openPipeline(match.file);
    }
    if (stage && nodes().some((n) => n.id === stage)) select(stage);
  }

  function selectedStageLabel() {
    const node = nodes().find((n) => n.id === pl.selected);
    return node ? nodeTitle(node) : null;
  }

  return {
    async show() {
      if (pl.loaded) return;
      pl.loaded = true;
      try {
        pl.palette = await api('/api/palette');
        renderPalette();
        await loadList();
        const wanted = nav.read().pipeline;
        const match = wanted && pl.list.find((p) => p.name === wanted || p.file === wanted);
        if (match) await nav.restoring(() => openPipeline(match.file));
        else { syncToolbar(); render(); renderConfig(); }
        const stage = nav.read().stage;
        if (match && stage && nodes().some((n) => n.id === stage)) await nav.restoring(() => select(stage));
      } catch (error) {
        status('invalid', `The designer could not load: ${error.message}`);
      }
    },
    openByName,
    returnFromMapping,
    returnToWizard,
    mappingSaved,
    mappingContext: () => mappingContext(),
    currentLabel,
    selectedStageLabel,
  };
}
