/*
 * NIEMVault authoring surface: the pipeline designer and the mapping editor, as one tool.
 *
 * Vanilla JS modules on purpose: no framework, no build step, nothing fetched at runtime. Spec
 * section 6 makes air-gapped delivery mandatory, and every dependency is one an agency has to
 * mirror and keep patched.
 *
 * This file decides nothing about whether a mapping is valid, and it never rewrites YAML itself.
 * Both jobs belong to the server, which asks the same loaders the runtime uses and patches the text
 * line by line. That is ADR 0021 -- an editor with its own opinion about validity would eventually
 * disagree with the engine, and one that regenerated YAML would erase the commentary explaining why
 * each step exists.
 *
 * One path through it: Pipeline › Stage › Hop › Step. A pipeline's Mapping stage opens here, in the
 * mapping editor, with the pipeline still named above it and a way back to exactly where the author
 * left it; the sample records the pipeline preview reads are the ones each step shows here. The
 * words are one per thing: an origin is where a pipeline's records come from and a source is the
 * definition that describes it; a pipeline has stages, a mapping has hops, a hop has steps.
 */

import { createCanvas } from './canvas.js';
import { initPipelines } from './pipelines.js';
import * as nav from './nav.js';
import {
  el, api, post, hint, action, field, renderProblems, setStatusLine, humanize, orderTransforms,
} from './shared.js';

const state = {
  file: null,
  name: null,
  savedYaml: '',
  mapping: null,
  transforms: [],
  hopId: null,
  contract: null,
  selected: null,
  debounce: null,
  saveAs: '',
  versionTouched: false,
  draft: false,
};

/*
 * Where the mapping was opened from. A pipeline's Mapping stage, a new source being added in the
 * wizard, or nowhere (opened on its own). It is what the context bar draws and where "Back" goes.
 */
const context = {
  pipeline: null,        // { label, mappingName, mappingRef, origin }
  wizard: null,          // { sourceId, origin }
};

/*
 * The field preview: one hop of the mapping as it stands in the editor, run over a few real records
 * from the origin. Off until asked for; once on, it follows every edit.
 */
const preview = {
  on: false,
  result: null,
  key: null,
  record: 0,
  loading: false,
  timer: null,
};

let canvas;
let pipelines;

const currentHop = () => (state.mapping?.hops || []).find((hop) => hop.id === state.hopId);

// --- module, mappings, palette ---------------------------------------------

async function loadModule() {
  try {
    const module = await api('/api/module');
    el('module-name').textContent = `${module.displayName} · ${module.name}@${module.version}`;
    el('module-detail').textContent =
      `platform ${module.platformVersions} · canonical model ${module.canonicalModel}`
      + (module.steward ? ` · steward ${module.steward}` : '');
  } catch (error) {
    el('module-name').textContent = 'Module cannot be opened';
    el('module-detail').textContent = error.message;
  }
}

let mappingFiles = [];

async function loadMappingList() {
  mappingFiles = (await api('/api/mappings')).mappings;
  const list = el('mapping-list');
  list.replaceChildren();
  const select = el('mapping-open');
  select.replaceChildren();

  if (state.draft) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = `${state.name || 'new mapping'} (not saved yet)`;
    select.append(option);
  }

  mappingFiles.forEach((mapping) => {
    const button = document.createElement('button');
    button.type = 'button';
    // A mapping that will not load is still listed. Hiding it would leave an author unable to open
    // the one file they need to fix.
    button.className = mapping.loadable ? '' : 'broken';
    button.setAttribute('aria-current', String(mapping.file === state.file));

    const name = document.createElement('span');
    name.textContent = mapping.name;
    const version = document.createElement('span');
    version.className = 'version';
    version.textContent = mapping.loadable ? mapping.version : 'does not load';

    button.append(name, version);
    button.addEventListener('click', () => openMapping(mapping.file));

    const item = document.createElement('li');
    item.append(button);
    list.append(item);

    const option = document.createElement('option');
    option.value = mapping.file;
    option.textContent = `${mapping.name}@${mapping.version}${mapping.loadable ? '' : ' (does not load)'}`;
    select.append(option);
  });
  select.value = state.draft ? '' : (state.file || '');
}

function renderPalette() {
  const list = el('palette');
  list.replaceChildren();
  const { common, rest } = orderTransforms(state.transforms);
  const chip = (type) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'chip';
    button.textContent = type;
    button.addEventListener('click', () => addStep(type));
    const item = document.createElement('li');
    item.append(button);
    return item;
  };
  common.forEach((type) => list.append(chip(type)));
  if (rest.length) {
    const more = document.createElement('li');
    more.className = 'palette-divider';
    more.textContent = 'more';
    list.append(more);
    rest.forEach((type) => list.append(chip(type)));
  }
}

/**
 * Opens a mapping from disk.
 *
 * @param at where in it to land: a hop and a selected node, restored from the URL or carried from
 *     the pipeline
 */
async function openMapping(file, at = {}) {
  try {
    const mapping = await api(`/api/mapping?file=${encodeURIComponent(file)}`);
    state.file = file;
    state.name = mapping.name;
    state.draft = false;
    state.savedYaml = mapping.source;
    state.hopId = at.hop || null;
    state.selected = at.step || null;
    state.saveAs = mapping.nextVersion;
    state.versionTouched = false;
    el('yaml').value = mapping.source;
    resetPreview();
    apply(mapping.svg, mapping, []);
    syncVersion();
    setStatus('idle', `Opened ${mapping.name}@${mapping.version}. Saving writes a new version; `
      + 'the opened one is never changed.');
    el('save').disabled = true;
    await loadMappingList();
    if (!at.restoring) nav.go({ view: 'flow', mapping: mapping.name, hop: state.hopId, step: state.selected });
    renderContext();
  } catch (error) {
    setStatus('invalid', `Cannot open this mapping: ${error.message}`);
    showProblems([error.message]);
  }
}

/**
 * Opens a mapping that exists only as text so far -- a new source's first mapping, started in the
 * wizard. It is validated like any draft, and Save writes it for the first time.
 */
async function openDraft(yaml, name) {
  state.file = null;
  state.name = name;
  state.draft = true;
  state.savedYaml = '';
  state.hopId = null;
  state.selected = null;
  state.saveAs = '';
  state.versionTouched = false;
  el('yaml').value = yaml;
  resetPreview();
  await loadMappingList();
  await validate();
  setStatus('idle', `${name} is not saved yet. Fill in what the problems list says is missing, then save it.`);
}

// --- validation and edits ---------------------------------------------------

function scheduleValidation() {
  clearTimeout(state.debounce);
  setStatus('idle', 'Checking…');
  state.debounce = setTimeout(validate, 350);
}

async function validate() {
  const yaml = el('yaml').value;
  try {
    accept(await api('/api/validate', { method: 'POST', body: yaml }), yaml);
  } catch (error) {
    setStatus('invalid', 'Does not load.');
    showProblems([error.message]);
    el('save').disabled = true;
  }
}

/*
 * Every gesture on the canvas ends here. The server owns the edit: it patches the text,
 * revalidates, and returns both. Nothing is applied locally and hoped to match.
 */
async function edit(request) {
  try {
    const report = await api('/api/edit', {
      method: 'POST',
      body: JSON.stringify({ yaml: el('yaml').value, hop: state.hopId, ...request }),
    });
    el('yaml').value = report.yaml;
    accept(report, report.yaml);
    loadSuggestions();
  } catch (error) {
    // An edit the text patcher will not make -- an option that spans lines, a step that cannot move
    // any further. Say so and change nothing.
    showProblems([error.message]);
    setStatus('invalid', `Not applied: ${humanize(error.message)}`);
  }
}

async function editContract(request) {
  try {
    const report = await api('/api/contract/edit', {
      method: 'POST',
      body: JSON.stringify({ yaml: el('yaml').value, hop: state.hopId, ...request }),
    });
    // A contract edit is written to disk immediately -- there is no draft of a contract -- but it
    // can open or close a coverage hole in a mapping nobody touched, so the mapping is rechecked.
    accept(report, el('yaml').value);
    await loadContract();
    renderInspector();
  } catch (error) {
    showProblems([error.message]);
    setStatus('invalid', `Not applied: ${humanize(error.message)}`);
  }
}

// --- the catalogue ----------------------------------------------------------

/*
 * What the source sends, what the agency calls it, and what it becomes (ADR 0019).
 *
 * The meanings are editable here, which is the point. A records manager can say what BEAT means
 * without opening a mapping, and what they write goes into the artifact that declares the column --
 * so the meaning travels with the thing it describes, and is reviewed with it.
 */
async function showCatalogue() {
  const vocabulary = el('catalogue-vocabulary');
  const produces = el('catalogue-produces');
  const arrivals = el('catalogue-arrivals');
  vocabulary.replaceChildren();
  produces.replaceChildren();
  arrivals.replaceChildren();

  let catalogue;
  try {
    catalogue = await api('/api/catalogue', { method: 'POST', body: el('yaml').value });
  } catch (error) {
    vocabulary.append(hint('Catalogue unavailable: ' + error.message));
    return;
  }
  if (!catalogue.available) {
    vocabulary.append(hint('The mapping does not parse, so there is nothing to catalogue yet.'));
    return;
  }

  el('catalogue-source').textContent = catalogue.sourceId;
  el('catalogue-detail').textContent =
    `${catalogue.mapping} · ${catalogue.recordType}`
    + (catalogue.contracts.length ? ` · ${catalogue.contracts.length} contract(s)` : '');
  renderGaps(catalogue.gaps);

  renderArrivals(arrivals, catalogue);
  catalogue.vocabulary.forEach((term) => vocabulary.append(termRow(term)));
  catalogue.produces.forEach((produced) => produces.append(producedRow(produced)));

  // Sized after they are in the document. scrollHeight on a detached element measures nothing, so
  // fitting at construction time silently leaves every long meaning cut off at two lines -- and the
  // end of a meaning is the half that matters, because that is where the caveats are.
  vocabulary.querySelectorAll('.term-meaning').forEach(fitToContent);
}

/*
 * How the source arrives, and what that means for keeping it (ADR 0027).
 *
 * The connector declares its interaction mode and retention posture, and until now the only way to
 * read either was to open the YAML. Retention is a legal question rather than an architectural one,
 * so the person who most needs the answer is the one least likely to be reading source files.
 *
 * One card per definition, because a source may arrive by more than one transport under the same
 * mapping -- that is the separation between transport and meaning, and collapsing it here would
 * hide the very thing worth showing.
 */
function renderArrivals(container, catalogue) {
  catalogue.arrivalProblems.forEach((problem) => {
    container.append(gap('A source definition could not be read', problem));
  });

  if (!catalogue.arrivals.length) {
    container.append(hint(
      'No source definition says how this source arrives. The catalogue can say what it means and '
      + 'not how it gets here, which leaves retention unanswered for anyone who did not build the '
      + 'pipeline.'));
    return;
  }
  catalogue.arrivals.forEach((arrival) => container.append(arrivalRow(arrival)));
}

function arrivalRow(arrival) {
  const row = document.createElement('div');
  row.className = 'arrival';
  if (!arrival.described) {
    row.classList.add('arrival--unknown');
  } else if (!arrival.replayable) {
    row.classList.add('arrival--transient');
  }

  const head = document.createElement('div');
  head.className = 'arrival-head';

  const transport = document.createElement('strong');
  transport.textContent = arrival.transport;
  const instance = document.createElement('span');
  instance.className = 'arrival-instance';
  instance.textContent = arrival.instance;
  head.append(transport, instance);

  if (arrival.described) {
    head.append(badge(arrival.mode), badge(arrival.retention, !arrival.replayable));
  }
  if (arrival.freshness) {
    head.append(badge('stale after ' + arrival.freshness));
  }

  const note = document.createElement('p');
  note.className = 'arrival-note';
  if (!arrival.described) {
    note.textContent = 'This deployment cannot describe this source: ' + arrival.problem;
  } else if (arrival.replayable) {
    note.textContent = 'Records land in bronze and follow the normal path, so a run can be replayed '
      + 'from what actually arrived.';
  } else {
    // Said in full rather than as a badge. A surface offering replay for a source that keeps
    // nothing would be lying, and this is where somebody finds that out in time.
    note.textContent = 'Records must not be kept. They are used for the request at hand and never '
      + 'landed, so there is nothing to replay from — the only durable trace is the disclosure '
      + 'record: that it was asked for, and how much came back, never the content.';
  }

  row.append(head, note);
  return row;
}

function badge(text, isWarning) {
  const tag = document.createElement('span');
  tag.className = isWarning ? 'arrival-badge arrival-badge--warn' : 'arrival-badge';
  tag.textContent = text;
  return tag;
}

function renderGaps(gaps) {
  const container = el('catalogue-gaps');
  container.replaceChildren();

  // Reported, never hidden. A catalogue that listed only its documented terms would tell an agency
  // their source is fully understood.
  if (!gaps.undocumented.length && !gaps.unused.length && !gaps.arrivalUndeclared) {
    const ok = document.createElement('div');
    ok.className = 'gap gap--ok';
    ok.textContent = 'Every term documented and read.';
    container.append(ok);
    return;
  }
  if (gaps.undocumented.length) {
    container.append(gap(`${gaps.undocumented.length} undocumented`,
      'Nobody has said what these mean: ' + gaps.undocumented.join(', ')));
  }
  if (gaps.unused.length) {
    container.append(gap(`${gaps.unused.length} sent but never read`,
      'The source sends these and no step consumes them: ' + gaps.unused.join(', ')));
  }
  if (gaps.arrivalUndeclared) {
    container.append(gap('arrival undeclared',
      'Nothing says how this source reaches the platform, or whether its records may be kept.'));
  }
}

function gap(title, detail) {
  const item = document.createElement('div');
  item.className = 'gap';
  const heading = document.createElement('strong');
  heading.textContent = title;
  const text = document.createElement('span');
  text.textContent = detail;
  item.append(heading, text);
  return item;
}

function termRow(term) {
  const row = document.createElement('div');
  row.className = 'term' + (term.documented ? '' : ' is-undocumented');

  const head = document.createElement('div');
  head.className = 'term-head';

  const name = document.createElement('span');
  name.className = 'term-name';
  name.textContent = term.term;

  const becomes = document.createElement('span');
  becomes.className = 'term-becomes';
  becomes.textContent = term.becomes.length
    ? '→ ' + term.becomes.join(', ')
    : 'read by nothing';

  head.append(name, becomes);
  if (term.governed) {
    const checked = document.createElement('span');
    checked.className = 'term-governed';
    checked.textContent = 'checked on the way in';
    head.append(checked);
  }

  const meaning = document.createElement('textarea');
  meaning.className = 'term-meaning';
  meaning.value = term.meaning;
  meaning.placeholder = 'What does this agency mean by ' + term.term + '?';
  meaning.setAttribute('aria-label', 'meaning of ' + term.term);
  // Committed on blur. Each edit rewrites the artifact and revalidates it, which is not something
  // to do per keystroke.
  meaning.addEventListener('blur', () => {
    if (meaning.value !== term.meaning) {
      recordMeaning(term.term, meaning.value);
    }
  });

  row.append(head, meaning);
  meaning.addEventListener('input', () => fitToContent(meaning));
  return row;
}

/** Grows a textarea to fit what is in it. Measured after layout, not guessed from character count. */
function fitToContent(field) {
  field.style.height = 'auto';
  field.style.height = Math.max(field.scrollHeight, 34) + 'px';
}

async function recordMeaning(term, meaning) {
  try {
    const report = await api('/api/catalogue/edit', {
      method: 'POST',
      body: JSON.stringify({ yaml: el('yaml').value, term, meaning }),
    });
    el('yaml').value = report.yaml;
    accept(report, report.yaml);
    await showCatalogue();
  } catch (error) {
    showProblems([error.message]);
    setStatus('invalid', 'not applied');
  }
}

function producedRow(produced) {
  const row = document.createElement('div');
  row.className = 'produced';

  const head = document.createElement('div');
  head.className = 'term-head';
  const name = document.createElement('span');
  name.className = 'term-name';
  name.textContent = `${produced.type}@${produced.version}`;
  const identity = document.createElement('span');
  identity.className = 'term-becomes';
  identity.textContent = produced.identity;
  head.append(name, identity);

  const provenance = document.createElement('p');
  provenance.className = 'detail';
  provenance.textContent = produced.provenance;

  row.append(head, provenance);
  return row;
}


// --- views ------------------------------------------------------------------

const VIEWS = ['pipelines', 'flow', 'catalogue', 'coverage'];
let currentView = 'pipelines';

function showView(view, { record = true } = {}) {
  const current = VIEWS.includes(view) ? view : 'pipelines';
  currentView = current;

  VIEWS.forEach((name) => {
    const pane = name === 'flow' ? el('mapping-view') : el(name + '-view');
    const tab = el('view-' + name);
    pane.hidden = name !== current;
    tab.classList.toggle('is-current', name === current);
    tab.setAttribute('aria-selected', String(name === current));
  });

  if (current === 'pipelines') pipelines.show();
  if (current === 'catalogue') showCatalogue();
  if (current === 'coverage') showCoverage();
  // The URL names only the place the view shows: a pipeline view carries no mapping hop or step.
  if (record) nav.go(current === 'pipelines' ? { view: current, mapping: null, hop: null, step: null, draft: null } : { view: current });
  renderContext();
}

/*
 * How much of NIEM the canonical model stands on (ADR 0011).
 *
 * The build already refuses a provenance that does not resolve. What nobody could see was the other
 * direction -- given the release, which of it does this platform actually touch. That is the
 * question an evaluator asks, and the answer was previously only in the DSL sources.
 *
 * Loaded once. The model does not change while the page is open, and re-fetching on every tab
 * switch would make an unchanging answer look like it was being recomputed.
 */
let coverageReport = null;

async function showCoverage() {
  const namespaces = el('coverage-namespaces');
  if (coverageReport) return;

  namespaces.replaceChildren(hint('Reading the packaged NIEM release…'));
  try {
    coverageReport = await api('/api/coverage');
  } catch (error) {
    namespaces.replaceChildren(hint('Coverage unavailable: ' + error.message));
    return;
  }
  renderCoverage(coverageReport);
}

function renderCoverage(report) {
  el('coverage-headline').textContent =
    `${report.namespacesTouched} of ${report.namespaceCount} domains`;

  const totals = el('coverage-totals');
  totals.replaceChildren();
  const total = gap(`${report.cited} cited`,
    `of ${report.declared.toLocaleString()} types and elements the release declares`);
  total.classList.add('gap--fact');
  totals.append(total);

  // Always rendered when present, never folded away. A citation the release cannot account for
  // cannot happen -- the build refuses to generate one -- so if it ever appears it is the most
  // important thing on the page.
  const unresolved = el('coverage-unresolved');
  unresolved.replaceChildren();
  if (report.unresolved.length) {
    const alarm = document.createElement('div');
    alarm.className = 'gap gap--bad';
    alarm.textContent = `${report.unresolved.length} citation(s) the packaged release cannot `
      + 'account for. The build should have refused this model: '
      + report.unresolved.map((problem) => `${problem.where} → ${problem.citation}`).join('; ');
    unresolved.append(alarm);
  }

  const namespaces = el('coverage-namespaces');
  namespaces.replaceChildren();
  report.namespaces.forEach((namespace) => namespaces.append(namespaceRow(namespace)));

  const extensions = el('coverage-extensions');
  extensions.replaceChildren();
  if (!report.extensions.length) {
    extensions.append(hint('The model extends nothing beyond NIEM.'));
    return;
  }
  report.extensions.forEach((extension) => extensions.append(extensionRow(extension)));
}

/* One domain. Collapsed to a line until asked, because seventeen of eighteen have nothing to say. */
function namespaceRow(namespace) {
  const entry = document.createElement('details');
  entry.className = 'ns' + (namespace.cited ? ' ns--used' : '');

  const summary = document.createElement('summary');

  const name = document.createElement('span');
  name.className = 'ns-name';
  name.textContent = namespace.name;

  const prefix = document.createElement('span');
  prefix.className = 'ns-prefix';
  prefix.textContent = namespace.prefix;

  const declared = document.createElement('span');
  declared.className = 'ns-declared';
  declared.textContent = `${namespace.declaredTypes} types · ${namespace.declaredElements} elements`;

  const cited = document.createElement('span');
  cited.className = 'ns-cited';
  cited.textContent = namespace.cited ? `${namespace.cited} cited` : 'untouched';

  summary.append(name, prefix, declared, cited);
  entry.append(summary);

  const body = document.createElement('div');
  body.className = 'ns-body';
  if (!namespace.cited) {
    // Said outright rather than left as an empty panel. "Nothing here" and "nothing loaded" look
    // identical when a panel is simply blank.
    body.append(hint('The canonical model does not stand on this domain.'));
  } else {
    namespace.citations.forEach((citation) => body.append(citationRow(citation)));
  }

  const uri = document.createElement('p');
  uri.className = 'ns-uri';
  uri.textContent = namespace.uri;
  body.append(uri);

  entry.append(body);
  return entry;
}

function citationRow(citation) {
  const row = document.createElement('div');
  row.className = 'citation';

  const kind = document.createElement('span');
  kind.className = 'citation-kind';
  kind.textContent = citation.kind;

  const where = document.createElement('span');
  where.className = 'citation-where';
  where.textContent = citation.where;

  const niem = document.createElement('span');
  niem.className = 'citation-niem';
  niem.textContent = citation.niemName;

  row.append(kind, where, niem);
  return row;
}

/* An extension is a decision with a reason, so the reason is the body of the row, not a tooltip. */
function extensionRow(extension) {
  const row = document.createElement('div');
  row.className = 'extension';

  const where = document.createElement('h4');
  where.textContent = extension.where;

  const why = document.createElement('p');
  why.textContent = extension.justification;

  row.append(where, why);
  return row;
}

// --- accepting a validated draft --------------------------------------------

function accept(report, yaml) {
  if (report.svg) {
    apply(report.svg, report.mapping, report.problems);
    state.name = report.mapping?.name || state.name;
  } else {
    // The mapping did not parse, so there is nothing new to draw. The previous picture stays: an
    // author fixing a mistake wants to see what they are fixing.
    showProblems(report.problems);
  }
  if (report.saveAs && !state.versionTouched) {
    state.saveAs = report.saveAs;
    syncVersion();
  }
  const dirty = state.draft || yaml !== state.savedYaml;
  if (report.valid) {
    setStatus('valid', dirty
      ? `Valid. Save writes ${state.name}@${versionToSave()}.`
      : 'Valid. Nothing changed since it was opened.');
    el('save').disabled = !dirty;
  } else {
    const count = report.problems.length;
    setStatus('invalid', `${count} problem${count === 1 ? '' : 's'} — listed in the panel; the canvas marks where.`);
    el('save').disabled = true;
  }
  schedulePreview();
  renderContext();
}

function apply(svg, mapping, problems) {
  state.mapping = mapping;
  el('dag').innerHTML = svg;
  wireDagNodes();
  renderColumns(mapping.columns);

  const hops = mapping.hops || [];
  if (!hops.some((hop) => hop.id === state.hopId)) {
    state.hopId = hops.length ? hops[0].id : null;
  }
  // Problems first: the inspector reads them for the option names a step needs.
  showProblems(problems);
  showHop();
}

function versionToSave() {
  return el('mapping-version').value.trim() || state.saveAs || '?';
}

function syncVersion() {
  if (!state.versionTouched) el('mapping-version').value = state.saveAs || '';
  el('save').textContent = `Save as v${versionToSave()}`;
}

// --- the hop strip ----------------------------------------------------------

function wireDagNodes() {
  el('dag').querySelectorAll('.dag-node[data-hop]').forEach((node) => {
    const hopId = (node.dataset.hop || '').trim();
    node.classList.toggle('is-selected', hopId === state.hopId);
    node.setAttribute('tabindex', '0');
    node.setAttribute('role', 'button');
    const open = () => {
      state.hopId = hopId;
      state.selected = null;
      showHop();
      nav.go({ hop: hopId, step: null }, { replace: true });
    };
    node.addEventListener('click', open);
    node.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); }
    });
  });
}

function renderColumns(columns) {
  const list = el('column-list');
  list.replaceChildren();
  (columns || []).forEach((column) => {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'chip chip--origin';
    button.textContent = column;
    button.title = 'With a step selected: have it read this column';
    button.addEventListener('click', () => readInto(column));
    item.append(button);
    list.append(item);
  });
}

/** Makes the selected step read a source column: the rail's columns are a way in, not a legend. */
function readInto(name) {
  const node = state.selected ? nodeById(state.selected) : null;
  if (!node || node.kind !== 'transform') {
    setStatus('idle', `Select a step first; then clicking ${name} makes it read that column.`);
    return;
  }
  const step = currentHop().steps[node.step];
  if (step.from.includes(name)) return;
  edit({ op: 'setFrom', step: node.step, from: [...step.from, name] });
}

// --- the canvas -------------------------------------------------------------

async function loadContract() {
  if (!state.hopId) {
    state.contract = null;
    return;
  }
  try {
    const contract = await api(`/api/contract?hop=${encodeURIComponent(state.hopId)}`);
    state.contract = contract.present ? contract : null;
  } catch (error) {
    state.contract = null;
  }
}

function showHop() {
  const hop = currentHop();
  el('dag').querySelectorAll('.dag-node[data-hop]').forEach((node) =>
    node.classList.toggle('is-selected', (node.dataset.hop || '').trim() === state.hopId));

  if (!hop) {
    el('stage-title').textContent = 'Fields';
    el('canvas').replaceChildren();
    renderContext();
    return;
  }
  el('stage-title').textContent = `Fields of ${hop.id} → ${hop.entityType}`;
  canvas.render(hop.graph);
  canvas.select(state.selected);
  paintValues();
  renderInspector();
  renderSamples();
  // The contract and the suggestions arrive after the drawing rather than blocking it. Both are
  // detail on demand; the flow is what the author came to look at.
  loadContract().then(renderInspector);
  loadSuggestions();
  schedulePreview();
  renderContext();
}

/*
 * Proposals for canonical fields this step does not yet produce.
 *
 * The advisor proposes and a person accepts (ADR 0023). Nothing here applies anything on its own,
 * and every proposal shows its reasoning, because an author has to be able to disagree with it
 * before it enters an artifact that will be audited.
 */
async function loadSuggestions() {
  const container = el('suggestions');
  container.replaceChildren();
  if (!state.hopId) return;

  try {
    const result = await api('/api/suggest', {
      method: 'POST',
      body: JSON.stringify({ hop: state.hopId, yaml: el('yaml').value }),
    });
    el('advisor').textContent = result.advisor
      + (result.shapesObserved ? ` · ${result.shapesObserved} column shapes read` : ' · names only');

    if (!result.suggestions.length) {
      container.append(hint('Nothing to propose — every field this hop can fill is filled.'));
      return;
    }
    result.suggestions.forEach((suggestion) => container.append(suggestionCard(suggestion)));
  } catch (error) {
    container.append(hint('Suggestions unavailable: ' + error.message));
  }
}

function suggestionCard(suggestion) {
  const card = document.createElement('div');
  card.className = 'suggestion';

  const head = document.createElement('div');
  head.className = 'suggestion-head';
  const what = document.createElement('span');
  what.className = 'suggestion-what';
  what.textContent = `${suggestion.from.join(', ')} → ${suggestion.target}`;
  const score = document.createElement('span');
  // Shown rather than used as a cutoff: a low-confidence proposal for a field nothing else covers
  // is still the most useful thing on the panel.
  score.className = 'suggestion-score';
  score.textContent = Math.round(suggestion.confidence * 100) + '%';
  head.append(what, score);

  const how = document.createElement('p');
  how.className = 'detail';
  how.textContent = `${suggestion.type}${Object.keys(suggestion.options || {}).length
    ? ' · ' + Object.entries(suggestion.options).map(([k, v]) => `${k} ${v}`).join(' · ') : ''}`;

  const why = document.createElement('p');
  why.className = 'detail suggestion-why';
  why.textContent = suggestion.rationale;

  const actions = document.createElement('div');
  actions.className = 'inspector-actions';
  actions.append(action('Accept', () => edit({
    op: 'addStep',
    target: suggestion.target,
    type: suggestion.type,
    from: suggestion.from,
    options: suggestion.options || {},
  }), 'ghost'));

  card.append(head, how, why, actions);
  return card;
}

function nodeById(id) {
  return (currentHop()?.graph.nodes || []).find((node) => node.id === id);
}

function onSelect(node) {
  state.selected = node.id;
  canvas.select(node.id);
  renderInspector();
  renderSamples();
  renderContext();
  nav.go({ step: node.id }, { replace: true });
}

/**
 * Wires a value into a transform's inputs.
 *
 * Guarded on order, which the picture cannot show on its own: steps assign in sequence, so a step
 * reading something produced later reads nothing at all. Refusing here, with the reason, is better
 * than writing a mapping that loads cleanly and quietly produces empty fields.
 */
function onConnect(stepIndex, source) {
  if (source.step >= stepIndex) {
    showProblems([
      `"${source.label}" is produced after this step runs, so this step would read nothing. `
      + 'Move this step later first.',
    ]);
    setStatus('invalid', 'Not applied: that value is written after this step runs.');
    return;
  }
  const hop = currentHop();
  const step = hop.steps[stepIndex];
  if (step.from.includes(source.label)) return;

  edit({ op: 'setFrom', step: stepIndex, from: [...step.from, source.label] });
}

function onDisconnect(stepIndex, name) {
  const step = currentHop().steps[stepIndex];
  edit({ op: 'setFrom', step: stepIndex, from: step.from.filter((input) => input !== name) });
}

/*
 * Asks for the new step's target in the inspector, not in a browser prompt.
 *
 * A window.prompt blocks the whole page, cannot be styled or explained, and offers no way to show
 * which field names would actually be accepted. Here the panel can list them.
 */
function addStep(type, carried = {}) {
  const hop = currentHop();
  if (!hop) return;

  state.selected = null;
  canvas.select(null);
  const panel = el('inspector');
  el('inspector-title').textContent = `New ${type} step`;
  panel.replaceChildren();

  const name = document.createElement('input');
  name.type = 'text';
  name.placeholder = 'field name';
  name.value = carried.target || '';
  name.setAttribute('aria-label', 'field this step writes');

  // Options the transform turned out to need, asked for here: a step that cannot compile would stop
  // the whole mapping loading, and a mapping that does not load draws no new step to select and fix.
  const optionInputs = new Map();
  (carried.needed || []).forEach((key) => {
    const box = document.createElement('input');
    box.type = 'text';
    box.value = carried.options?.[key] || '';
    box.setAttribute('aria-label', `option ${key}`);
    optionInputs.set(key, box);
  });

  // What it reads, chosen with the step: most transforms will not compile reading nothing.
  const reads = document.createElement('select');
  reads.multiple = true;
  reads.size = 5;
  reads.setAttribute('aria-label', 'what this step reads');
  const written = [...new Set(hop.steps.map((step) => step.target))];
  [['Source columns', state.mapping.columns || []], ['Written by earlier steps', written]].forEach(([label, names]) => {
    if (!names.length) return;
    const group = document.createElement('optgroup');
    group.label = label;
    names.forEach((value) => {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      option.selected = (carried.from || []).includes(value);
      group.append(option);
    });
    reads.append(group);
  });

  const commit = async () => {
    const target = name.value.trim();
    if (!target) return;
    const options = {};
    optionInputs.forEach((box, key) => { if (box.value) options[key] = box.value; });
    const from = [...reads.selectedOptions].map((option) => option.value);
    const yaml = el('yaml').value;
    let report;
    try {
      report = await api('/api/edit', {
        method: 'POST',
        body: JSON.stringify({ yaml, hop: state.hopId, op: 'addStep', target, type, from, options }),
      });
    } catch (error) {
      showProblems([error.message]);
      return;
    }
    const about = (report.problems || []).filter((problem) => problem.includes(`steps[${target}]`));
    if (!report.svg && about.length) {
      // Not applied. A step that would stop the whole mapping loading is refused here, with the
      // loader's reason and a field for whatever it named, rather than written into a mapping that
      // then draws nothing an author could select to fix it.
      const needed = [...new Set(about.flatMap((problem) =>
        [...problem.matchAll(/requires option '([^']+)'/g)].map((match) => match[1])))];
      addStep(type, { target, from, needed: [...new Set([...optionInputs.keys(), ...needed])], options });
      el('inspector').prepend(problemsNote(about));
      return;
    }
    el('yaml').value = report.yaml;
    accept(report, report.yaml);
    loadSuggestions();
  };
  name.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') commit();
    if (event.key === 'Escape') renderInspector();
  });

  const actions = document.createElement('div');
  actions.className = 'inspector-actions';
  actions.append(action('Add step', commit, 'primary'), action('Cancel', renderInspector, 'ghost'));

  panel.append(
    field('Writes', name),
    field('Reads (choose one or more)', reads),
    ...[...optionInputs].map(([key, box]) => field(`${key} (required by ${type})`, box)),
    hint(`Added to the end of hop ${hop.id}. Choose what it reads once it is added.`),
    suggestions(hop, name),
    actions,
  );
  (optionInputs.size ? [...optionInputs.values()].find((box) => !box.value) || name : name).focus();
}

function problemsNote(problems) {
  const box = document.createElement('div');
  renderProblems(box, problems);
  return box;
}

/**
 * Field names this hop's record can carry that nothing writes yet.
 *
 * The list an author most often wants: the contract's own vocabulary, rather than a name typed from
 * memory that turns out to be a field the canonical type has never heard of.
 */
function suggestions(hop, input) {
  const written = new Set(hop.steps.map((step) => step.target));
  const candidates = hop.graph.nodes
    .filter((node) => node.kind === 'missing')
    .map((node) => node.label)
    .filter((label) => !written.has(label));

  const wrapper = document.createElement('div');
  if (candidates.length === 0) return wrapper;

  wrapper.append(hint('Required by the contract and not yet written:'));
  const list = document.createElement('ul');
  list.className = 'palette';
  candidates.forEach((label) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'chip';
    button.textContent = label;
    button.addEventListener('click', () => {
      input.value = label;
      input.focus();
    });
    const item = document.createElement('li');
    item.append(button);
    list.append(item);
  });
  wrapper.append(list);
  return wrapper;
}

// --- inspector --------------------------------------------------------------

function renderInspector() {
  const panel = el('inspector');
  const title = el('inspector-title');
  panel.replaceChildren();

  const node = state.selected ? nodeById(state.selected) : null;
  if (!node) {
    const hop = currentHop();
    title.textContent = hop ? `Hop ${hop.id}` : 'Nothing selected';
    if (hop) {
      panel.append(hint(`Gated by contract ${hop.contract} on the way in and out; emits ${hop.entityType}. `
        + 'Select a step, column or field on the canvas to see and change what it does.'));
    } else {
      panel.append(hint('Open a hop in the strip above.'));
    }
    return;
  }

  if (node.kind === 'column' || node.kind === 'unbound') {
    renderColumnInspector(node, title, panel);
    return;
  }

  if (node.kind !== 'transform') {
    title.textContent = node.label;
    panel.append(hint(describeKind(node)));
    return;
  }

  const hop = currentHop();
  const step = hop.steps[node.step];
  title.textContent = `Step ${node.step + 1} · ${node.label} → ${step.target}`;

  panel.append(
    field('Writes', input(step.target, (value) =>
      edit({ op: 'setField', step: node.step, key: 'target', value }))),
    field('Transform', transformPicker(node.step, step.type)),
    field('Reads', readsPicker(node.step, step)),
  );

  Object.entries(step.options || {}).forEach(([key, value]) => {
    panel.append(field(key, input(value, (next) =>
      edit({ op: 'setOption', step: node.step, key, value: next }))));
  });
  panel.append(optionAdder(node.step, step));

  const actions = document.createElement('div');
  actions.className = 'inspector-actions';
  actions.append(
    action('Move earlier', () => edit({ op: 'moveStep', step: node.step, delta: -1 }), 'ghost'),
    action('Move later', () => edit({ op: 'moveStep', step: node.step, delta: 1 }), 'ghost'),
    action('Delete step', () => {
      state.selected = null;
      edit({ op: 'removeStep', step: node.step });
    }, 'ghost danger'),
  );
  panel.append(actions);

  // Order is meaning here, not presentation: a step reads what earlier steps produced.
  panel.append(hint(`Step ${node.step + 1} of ${hop.steps.length}. Steps run in order.`));
}

/**
 * Adds an option a step does not have yet -- a parseDate's pattern, a codeMap's default.
 *
 * The names offered are the ones the runtime itself says this step needs or accepts, read from the
 * loader's problems about it ("requires option 'pattern'", "it accepts [pattern, zone]"): the factory
 * is the only authority on a transform's options, and a list kept here would drift from it.
 */
function optionAdder(stepIndex, step) {
  const wrap = document.createElement('div');
  wrap.className = 'option-adder';
  const about = (state.problems || []).filter((problem) =>
    problem.includes(`steps[${step.target}]`) || problem.includes(`writing '${step.target}'`));
  const named = new Set();
  about.forEach((problem) => {
    const required = problem.match(/requires option '([^']+)'/);
    if (required) named.add(required[1]);
    const accepts = problem.match(/accepts \[([^\]]*)\]/);
    if (accepts) accepts[1].split(',').map((name) => name.trim()).filter(Boolean).forEach((name) => named.add(name));
  });
  Object.keys(step.options || {}).forEach((name) => named.delete(name));

  const key = document.createElement('input');
  key.type = 'text';
  key.placeholder = named.size ? [...named][0] : 'option';
  key.setAttribute('aria-label', 'Option name');
  const list = document.createElement('datalist');
  list.id = `option-names-${stepIndex}`;
  named.forEach((name) => {
    const option = document.createElement('option');
    option.value = name;
    list.append(option);
  });
  key.setAttribute('list', list.id);
  const value = document.createElement('input');
  value.type = 'text';
  value.placeholder = 'value';
  value.setAttribute('aria-label', 'Option value');
  const add = () => {
    const name = key.value.trim() || (named.size === 1 ? [...named][0] : '');
    if (!name || !value.value) return;
    edit({ op: 'addOption', step: stepIndex, key: name, value: value.value });
  };
  value.addEventListener('keydown', (event) => { if (event.key === 'Enter') add(); });
  const row = document.createElement('div');
  row.className = 'option-row';
  row.append(key, value, action('Add', add, 'ghost inline'), list);
  wrap.append(field(named.size ? `Add an option · needs or accepts: ${[...named].join(', ')}` : 'Add an option', row));
  return wrap;
}

/**
 * What a step reads, edited here as well as by wiring on the canvas.
 *
 * The canvas only draws the columns some step already reads, so a new mapping -- whose steps read
 * nothing yet -- had no column on the canvas to wire from. Offered: every source column, and every
 * value an earlier step writes. Never a later one: it would be read before it exists.
 */
function readsPicker(stepIndex, step) {
  const wrap = document.createElement('div');
  wrap.className = 'reads';
  step.from.forEach((name) => {
    const chip = document.createElement('span');
    chip.className = 'chip chip--read';
    chip.textContent = name;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'chip-remove';
    remove.setAttribute('aria-label', `Stop reading ${name}`);
    remove.textContent = '×';
    remove.addEventListener('click', () => onDisconnect(stepIndex, name));
    chip.append(remove);
    wrap.append(chip);
  });
  const hop = currentHop();
  const earlier = [...new Set(hop.steps.slice(0, stepIndex).map((s) => s.target))];
  const offered = [...(state.mapping.columns || []), ...earlier].filter((name) => !step.from.includes(name));
  const select = document.createElement('select');
  select.setAttribute('aria-label', 'Add something for this step to read');
  const none = document.createElement('option');
  none.value = '';
  none.textContent = step.from.length ? '+ read another…' : '+ choose what it reads…';
  select.append(none);
  const group = (label, names) => {
    if (!names.length) return;
    const optgroup = document.createElement('optgroup');
    optgroup.label = label;
    names.forEach((name) => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      optgroup.append(option);
    });
    select.append(optgroup);
  };
  group('Source columns', offered.filter((name) => (state.mapping.columns || []).includes(name)));
  group('Written by earlier steps', offered.filter((name) => earlier.includes(name)));
  select.addEventListener('change', () => {
    if (select.value) edit({ op: 'setFrom', step: stepIndex, from: [...step.from, select.value] });
  });
  wrap.append(select);
  return wrap;
}

/**
 * A source column, and what the contract expects of it.
 *
 * This is the contract editor. It is here rather than on a screen of its own because a column's
 * expectations are only meaningful next to the flow that reads it -- the question an author has is
 * "is this the field that is going to quarantine my records", and that is answered by looking at
 * both at once.
 */
function renderColumnInspector(node, title, panel) {
  title.textContent = `Column ${node.label}`;

  if (!state.contract) {
    panel.append(hint(describeKind(node)));
    panel.append(hint('No contract gates this hop, so nothing checks this column.'));
    return;
  }

  const expected = (state.contract.expects || []).find((entry) => entry.name === node.label);
  panel.append(hint(`Checked by ${state.contract.name}@${state.contract.version} on the way in.`));

  if (!expected) {
    // The column is read but the inbound gate does not allow it: the record is rejected before the
    // step ever runs. One button fixes it.
    panel.append(hint('The contract does not declare this column, so the record would be rejected '
      + 'before this step runs.'));
    const actions = document.createElement('div');
    actions.className = 'inspector-actions';
    actions.append(action('Declare it', () =>
      editContract({ op: 'addField', field: node.label, type: 'string' }), 'ghost'));
    panel.append(actions);
    return;
  }

  panel.append(field('Type', choice(
    ['string', 'integer', 'decimal', 'boolean', 'date', 'datetime'], expected.type,
    (value) => editContract({ op: 'setAttribute', field: expected.name, key: 'type', value }))));

  panel.append(field('Required', choice(['false', 'true'], String(expected.required),
    (value) => editContract({ op: 'setAttribute', field: expected.name, key: 'required', value }))));

  panel.append(field('Pattern', input(expected.pattern, (value) =>
    editContract({ op: 'setAttribute', field: expected.name, key: 'pattern', value }))));

  panel.append(hint(expected.pattern
    ? 'A value that does not match is held back with its reason, rather than passed on.'
    : 'No pattern. A source that changes format would pass through unnoticed.'));

  const actions = document.createElement('div');
  actions.className = 'inspector-actions';
  actions.append(action('Remove from contract', () =>
    editContract({ op: 'removeField', field: expected.name }), 'ghost danger'));
  panel.append(actions);
}

function choice(values, current, commit) {
  const select = document.createElement('select');
  values.forEach((value) => {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = value;
    option.selected = value === current;
    select.append(option);
  });
  if (!values.includes(current)) {
    const unknown = document.createElement('option');
    unknown.value = current;
    unknown.textContent = current;
    unknown.selected = true;
    select.prepend(unknown);
  }
  select.addEventListener('change', () => commit(select.value));
  return select;
}

function describeKind(node) {
  switch (node.kind) {
    case 'column': return 'A column the origin sends. Drag its right-hand dot onto a step to feed it.';
    case 'scratch': return 'A working value. It is used by later steps and never reaches the record.';
    case 'field': return node.terminal
      ? 'A field of the record this hop emits. This is the value the contract checks.'
      : 'An intermediate value. A later step overwrites it before the record is emitted.';
    case 'identity': return node.detail + '. This is what decides whether two records are the same thing.';
    case 'unbound': return 'Nothing produces this name, so the step reads nothing. Wire it to a column, or correct the name in the step that reads it.';
    case 'missing': return 'The contract requires this field and no step writes it. Deployed as it stands, every record would be held back. Add a step that writes it.';
    default: return '';
  }
}

function transformPicker(stepIndex, current) {
  const select = document.createElement('select');
  select.setAttribute('aria-label', 'transform');
  const { common, rest } = orderTransforms(state.transforms);
  [...common, ...rest].forEach((type) => {
    const option = document.createElement('option');
    option.value = type;
    option.textContent = type;
    option.selected = type === current;
    select.append(option);
  });
  if (!state.transforms.includes(current)) {
    // A type the factory does not know still has to be shown, or the panel would claim the mapping
    // says something it does not.
    const unknown = document.createElement('option');
    unknown.value = current;
    unknown.textContent = `${current} (unknown)`;
    unknown.selected = true;
    select.prepend(unknown);
  }
  select.addEventListener('change', () =>
    edit({ op: 'setField', step: stepIndex, key: 'type', value: select.value }));
  return select;
}

function input(value, commit) {
  const box = document.createElement('input');
  box.type = 'text';
  box.value = value;
  // Committed on blur and on Enter, not per keystroke: each edit revalidates the whole mapping,
  // and half a field name is not worth validating.
  box.addEventListener('blur', () => {
    if (box.value !== value) commit(box.value);
  });
  box.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') box.blur();
    if (event.key === 'Escape') {
      box.value = value;
      box.blur();
    }
  });
  return box;
}

// --- the field preview ------------------------------------------------------

function resetPreview() {
  preview.result = null;
  preview.key = null;
  preview.record = 0;
  if (canvas) canvas.setValues(new Map());
}

function schedulePreview() {
  if (!preview.on) return;
  clearTimeout(preview.timer);
  preview.timer = setTimeout(runPreview, 500);
}

function setPreviewOn(on) {
  preview.on = on;
  el('mapping-preview').setAttribute('aria-pressed', String(on));
  el('mapping-preview').classList.toggle('is-on', on);
  el('mapping-preview').textContent = on ? 'Preview: on' : 'Preview';
  if (on) runPreview();
  else {
    resetPreview();
    renderSamples();
    el('record-picker').hidden = true;
  }
}

/**
 * Runs the open hop, as it stands in the editor, over a few records from the origin. From the
 * pipeline's origin when the mapping was opened from one -- the same records its preview read --
 * and otherwise from the module's own source definition for the mapping's source.
 */
async function runPreview() {
  const yaml = el('yaml').value;
  const hop = state.hopId;
  if (!hop) return;
  const key = `${hop}\n${yaml}`;
  if (preview.key === key && preview.result) return;
  preview.loading = true;
  renderSamples();
  try {
    const origin = context.pipeline?.origin || context.wizard?.origin || null;
    const result = await api('/api/preview/field', post({ yaml, hop, origin, limit: 8 }));
    // An edit made while this was in flight wins; this answer is for text no longer on screen.
    if (el('yaml').value !== yaml || state.hopId !== hop) return;
    preview.result = result;
    preview.key = key;
    if (preview.record >= (result.records || []).length) preview.record = 0;
  } catch (error) {
    preview.result = { ran: false, why: error.message };
  } finally {
    preview.loading = false;
  }
  paintValues();
  renderSamples();
}

/** One record's values under the canvas's nodes, so a row reads as the value being transformed. */
function paintValues() {
  const result = preview.result;
  const picker = el('record-picker');
  if (!preview.on || !result?.ran || !result.records?.length) {
    canvas.setValues(new Map());
    picker.hidden = true;
    return;
  }
  const record = result.records[preview.record];
  picker.hidden = false;
  el('record-label').textContent = `record ${record.record} of ${result.records.length}`
    + (record.admitted ? '' : ' · held at the gate');
  const values = new Map();
  Object.entries(record.columns || {}).forEach(([name, value]) =>
    values.set(`col:${name}`, { text: value ?? '∅', bad: !record.admitted }));
  (record.steps || []).forEach((step) => {
    if (step.failure) {
      values.set(`step:${step.index}`, { text: '✗ failed here', bad: true });
    } else {
      values.set(`value:${step.index}`, { text: step.value ?? '∅' });
    }
  });
  canvas.setValues(values);
}

function stepRecord(delta) {
  const records = preview.result?.records || [];
  if (!records.length) return;
  preview.record = (preview.record + delta + records.length) % records.length;
  paintValues();
  renderSamples();
}

/**
 * The sample values for what is selected: for a step, what it read and what it wrote on every
 * sampled record; for a column, what arrived; with nothing selected, how the hop fared overall.
 * Violations are the gate's own words, which describe a value by its shape (ADR 0015).
 */
function renderSamples() {
  const container = el('samples');
  container.replaceChildren();
  const result = preview.result;
  el('sample-origin').textContent = result?.ran ? `${result.read} from ${result.origin}` : '';
  if (!preview.on) {
    container.append(hint('Preview runs this hop on a few real records from its origin and shows each '
      + 'step’s values here and on the canvas. Nothing is written or acknowledged.'));
    return;
  }
  if (preview.loading && !result) {
    container.append(hint('Reading a few records from the origin…'));
    return;
  }
  if (!result) return;
  if (!result.ran) {
    const box = document.createElement('div');
    box.className = 'problem';
    box.textContent = humanize(result.why || 'The preview could not run.');
    container.append(box);
    return;
  }

  const node = state.selected ? nodeById(state.selected) : null;
  const table = document.createElement('table');
  table.className = 'pl-table samples-table';
  const body = document.createElement('tbody');
  const row = (cells, bad, current) => {
    const tr = document.createElement('tr');
    if (bad) tr.className = 'is-bad';
    if (current) tr.classList.add('is-current');
    cells.forEach((text) => {
      const td = document.createElement('td');
      td.textContent = text;
      tr.append(td);
    });
    body.append(tr);
  };

  if (node?.kind === 'transform' || node?.kind === 'field' || node?.kind === 'scratch') {
    const index = node.step;
    // One input: named once in the heading, so each row is just the value before and after.
    const inputs = new Set(result.records.flatMap((record) =>
      (record.steps || []).filter((s) => s.index === index).flatMap((s) => Object.keys(s.inputs || {}))));
    const single = inputs.size === 1 ? [...inputs][0] : null;
    const target = (currentHop()?.steps[index] || {}).target || 'value';
    // Built with textContent: column and field names are the author's text, not markup.
    const head = document.createElement('thead');
    const headRow = document.createElement('tr');
    ['#', single ? `${single} (read)` : 'Reads', `${target} (written)`].forEach((text) => {
      const th = document.createElement('th');
      th.textContent = text;
      headRow.append(th);
    });
    head.append(headRow);
    table.append(head);
    result.records.forEach((record, i) => {
      if (!record.admitted) {
        row([record.record, 'held at the gate', (record.violations || []).map(humanize).join('; ')], true, i === preview.record);
        return;
      }
      const step = (record.steps || []).find((s) => s.index === index);
      if (!step) {
        row([record.record, '—', 'an earlier step failed; this one did not run'], true, i === preview.record);
        return;
      }
      const reads = single
        ? String(step.inputs[single] ?? '∅')
        : Object.entries(step.inputs || {}).map(([k, v]) => `${k}=${v ?? '∅'}`).join('  ');
      row([record.record, reads || '(nothing)', step.failure ? `✗ ${step.failure}` : (step.value ?? '∅')],
        !!step.failure, i === preview.record);
    });
  } else if (node?.kind === 'column' || node?.kind === 'unbound' || node?.kind === 'missing') {
    table.innerHTML = '<thead><tr><th>#</th><th>Value</th><th>Gate</th></tr></thead>';
    result.records.forEach((record, i) => {
      const value = (record.columns || {})[node.label];
      row([record.record, value ?? '∅', record.admitted ? 'admitted' : (record.violations || []).map(humanize).join('; ')],
        !record.admitted, i === preview.record);
    });
  } else {
    const failed = result.records.filter((r) => (r.steps || []).some((s) => s.failure));
    const held = result.records.filter((r) => !r.admitted);
    container.append(hint(`${result.read} read · ${result.admitted} admitted by the gate · `
      + `${held.length} held at the gate · ${failed.length} failed at a step. Select a step to see its values.`));
    table.innerHTML = '<thead><tr><th>#</th><th>What happened</th></tr></thead>';
    [...held, ...failed].forEach((record) => {
      const failure = (record.steps || []).find((s) => s.failure);
      row([record.record, !record.admitted
        ? `held at the gate: ${(record.violations || []).map(humanize).join('; ')}`
        : `step ${failure.index + 1} (${failure.type} → ${failure.target}): ${failure.failure}`], true);
    });
    if (!held.length && !failed.length) return;
  }
  table.append(body);
  container.append(table);
}

// --- where the author is ----------------------------------------------------

/*
 * The context bar: Pipeline › Stage › Hop › Step, and the way back.
 *
 * Drawn from state, never stored: it says where the author is, and every crumb is a place they can
 * go back to with one click.
 */
function renderContext() {
  const crumbs = [];
  const add = (label, onClick) => crumbs.push({ label, onClick });

  if (context.wizard) add(`Adding ${context.wizard.sourceId}`, returnToWizard);
  const pipelineLabel = context.pipeline?.label || pipelines?.currentLabel();
  if (pipelineLabel && (currentView === 'pipelines' || context.pipeline)) {
    add(`Pipeline ${pipelineLabel}`, backToPipeline);
  }
  if (currentView === 'pipelines') {
    const stage = pipelines?.selectedStageLabel();
    if (stage) add(`Stage ${stage}`, null);
  } else if (currentView === 'flow' || currentView === 'catalogue') {
    if (state.name) {
      add(`Mapping ${state.name}${state.draft ? ' (not saved yet)' : `@${state.mapping?.version || ''}`}`,
        () => { state.selected = null; showView('flow'); showHop(); });
    }
    if (currentView === 'flow') {
      const hop = currentHop();
      if (hop) add(`Hop ${hop.id}`, () => { state.selected = null; canvas.select(null); renderInspector(); renderSamples(); renderContext(); });
      const node = state.selected ? nodeById(state.selected) : null;
      if (node) {
        const label = node.kind === 'transform'
          ? `Step ${node.step + 1} · ${node.label} → ${hop.steps[node.step].target}`
          : node.kind === 'column' ? `Column ${node.label}` : `${node.kind === 'missing' ? 'Missing field' : 'Field'} ${node.label}`;
        add(label, null);
      }
    }
  }

  const bar = el('context-bar');
  bar.hidden = currentView === 'coverage' || crumbs.length === 0;
  const list = el('crumbs');
  list.replaceChildren(...crumbs.map((crumb, i) => {
    const item = document.createElement('li');
    if (crumb.onClick && i < crumbs.length - 1) {
      const link = document.createElement('button');
      link.type = 'button';
      link.className = 'crumb';
      link.textContent = crumb.label;
      link.addEventListener('click', crumb.onClick);
      item.append(link);
    } else {
      const here = document.createElement('span');
      here.className = 'crumb is-here';
      here.textContent = crumb.label;
      item.append(here);
    }
    return item;
  }));

  const back = el('ctx-back');
  const away = currentView !== 'pipelines';
  back.hidden = !(away && (context.pipeline || context.wizard));
  back.textContent = context.wizard ? '← Back to adding the source' : '← Back to pipeline';
}

/** Leaves the mapping for the pipeline it was opened from, at the stage the author left. */
function backToPipeline() {
  showView('pipelines');
  pipelines.returnFromMapping();
}

function returnToWizard() {
  showView('pipelines');
  pipelines.returnToWizard();
}

// --- saving -----------------------------------------------------------------

async function save() {
  const yaml = el('yaml').value;
  try {
    const result = await api('/api/save', {
      method: 'POST', body: JSON.stringify({ yaml, version: versionToSave() }),
    });
    if (!result.saved) {
      showProblems(result.problems);
      setStatus('invalid', `Not saved: ${humanize(result.problems[0] || 'fix the problems listed first.')}`);
      return;
    }
    // A save writes a new version rather than replacing the file that was opened, so the mapping
    // that may already have been reviewed stays exactly as it was.
    el('yaml').value = result.yaml;
    state.savedYaml = result.yaml;
    state.file = result.file;
    state.draft = false;
    state.saveAs = result.nextVersion;
    state.versionTouched = false;
    syncVersion();
    el('save').disabled = true;
    await loadMappingList();
    await validate();
    const back = await pipelines.mappingSaved(result, { fromWizard: !!context.wizard, fromPipeline: !!context.pipeline });
    setStatus('saved', `Saved ${result.qualifiedName}.${back ? ` ${back}` : ''}`);
    nav.go({ mapping: result.name }, { replace: true });
    renderContext();
  } catch (error) {
    setStatus('invalid', `Not saved: ${humanize(error.message)}`);
    showProblems([error.message]);
  }
}

// --- problems and status ----------------------------------------------------

function showProblems(problems) {
  state.problems = problems || [];
  renderProblems(el('problems'), problems, 'No problems. This mapping will load.');
}

function setStatus(kind, text) {
  setStatusLine(el('status'), kind, text);
}

// --- start ------------------------------------------------------------------

canvas = createCanvas(el('canvas'), { onSelect, onConnect, onDisconnect });

el('yaml').addEventListener('input', scheduleValidation);
el('save').addEventListener('click', save);
el('mapping-validate').addEventListener('click', validate);
el('mapping-preview').addEventListener('click', () => setPreviewOn(!preview.on));
el('mapping-version').addEventListener('input', () => {
  state.versionTouched = true;
  syncVersion();
});
el('mapping-open').addEventListener('change', (event) => {
  if (event.target.value) {
    // Opening another mapping by hand leaves the pipeline it was opened from: it is no longer
    // that stage the author is looking at.
    context.pipeline = null;
    openMapping(event.target.value);
  }
});
el('record-prev').addEventListener('click', () => stepRecord(-1));
el('record-next').addEventListener('click', () => stepRecord(1));
el('view-pipelines').addEventListener('click', () => showView('pipelines'));
el('view-flow').addEventListener('click', () => showView('flow'));
el('view-catalogue').addEventListener('click', () => showView('catalogue'));
el('view-coverage').addEventListener('click', () => showView('coverage'));
el('relayout').addEventListener('click', () => canvas.reset());
el('ctx-back').addEventListener('click', () => (context.wizard ? returnToWizard() : backToPipeline()));

pipelines = initPipelines({
  // A pipeline's Mapping stage opens here with the pipeline still named above it, its origin as the
  // source of sample records, and a way back to exactly where the author left it.
  openMapping: async ({ file, label, mappingName, mappingRef, origin }) => {
    context.pipeline = { label, mappingName, mappingRef, origin };
    context.wizard = null;
    await openMapping(file);
    showView('flow');
  },
  // A new source's first mapping, started from what it sent.
  openDraft: async ({ yaml, name, sourceId, origin }) => {
    context.wizard = { sourceId, origin };
    context.pipeline = null;
    showView('flow', { record: false });
    await openDraft(yaml, name);
    nav.go({ view: 'flow', draft: '1', pipeline: null, stage: null, mapping: null, hop: null, step: null });
  },
  leftMapping: () => {
    context.wizard = null;
    context.pipeline = null;
  },
  changed: () => renderContext(),
});

nav.onPop(async (where) => {
  await nav.restoring(() => restore(where));
});

/** Goes back to a place the URL names: a view, a pipeline and its stage, a mapping and its hop. */
async function restore(where) {
  const view = where.view || 'pipelines';
  if (where.pipeline) await pipelines.openByName(where.pipeline, where.stage);
  if (view === 'flow' && where.mapping) {
    const file = mappingFiles.find((m) => m.loadable && m.name === where.mapping)
      || [...mappingFiles].reverse().find((m) => m.name === where.mapping);
    if (file && (state.file !== file.file || state.hopId !== (where.hop || state.hopId))) {
      if (where.pipeline) {
        const ctx = pipelines.mappingContext();
        if (ctx) context.pipeline = ctx;
      }
      await openMapping(file.file, { hop: where.hop, step: where.step, restoring: true });
    } else if (where.hop && where.hop !== state.hopId) {
      state.hopId = where.hop;
      state.selected = where.step || null;
      showHop();
    }
  }
  showView(view, { record: false });
}

(async function start() {
  await loadModule();
  // Read from the factory, so a transform added to the runtime appears here without anyone
  // remembering to update a list.
  state.transforms = (await api('/api/transforms')).types;
  renderPalette();
  await loadMappingList();

  const where = nav.read();
  // Kept for links from elsewhere: ?mapping= alone opens that mapping's fields. Matched on the
  // mapping's own name, never on a file path -- a consumer knows which mapping produced its
  // records and has no business knowing where on this filesystem the artifact sits.
  if (where.mapping && !where.view) where.view = 'flow';
  await nav.restoring(async () => {
    const wanted = where.mapping;
    const requested = wanted ? mappingFiles.find((m) => m.loadable && m.name === wanted) : null;
    if (wanted && !requested) {
      where.view = where.view === 'flow' ? 'pipelines' : where.view;
      setStatus('invalid', `No mapping named "${wanted}" in this module.`);
    }
    // The mapping editor always has something open, so the Mapping tab is never an empty page.
    if (!requested) {
      const first = mappingFiles.find((m) => m.loadable);
      if (first) await openMapping(first.file, { restoring: true });
    }
    await restore(where);
  });
  history.replaceState(nav.read(), '', `${location.pathname}${location.search}`);
})();
