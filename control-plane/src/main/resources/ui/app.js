/*
 * Mapping authoring surface.
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
 */

import { createCanvas } from './canvas.js';

const el = (id) => document.getElementById(id);

const state = {
  file: null,
  savedYaml: '',
  mapping: null,
  transforms: [],
  hopId: null,
  contract: null,
  selected: null,
  debounce: null,
};

let canvas;

async function api(path, options) {
  const response = await fetch(path, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || 'Request failed');
  return payload;
}

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

async function loadMappingList() {
  const { mappings } = await api('/api/mappings');
  const list = el('mapping-list');
  list.replaceChildren();

  mappings.forEach((mapping) => {
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
  });
}

function renderPalette() {
  const list = el('palette');
  list.replaceChildren();
  state.transforms.forEach((type) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'chip';
    button.textContent = type;
    button.addEventListener('click', () => addStep(type));
    const item = document.createElement('li');
    item.append(button);
    list.append(item);
  });
}

async function openMapping(file) {
  try {
    const mapping = await api(`/api/mapping?file=${encodeURIComponent(file)}`);
    state.file = file;
    state.savedYaml = mapping.source;
    state.hopId = null;
    state.selected = null;
    el('yaml').value = mapping.source;
    apply(mapping.svg, mapping, []);
    setStatus('valid', `${mapping.name}@${mapping.version}`);
    await loadMappingList();
  } catch (error) {
    setStatus('invalid', 'cannot open');
    showProblems([error.message]);
  }
}

// --- validation and edits ---------------------------------------------------

function scheduleValidation() {
  clearTimeout(state.debounce);
  setStatus('idle', 'checking…');
  state.debounce = setTimeout(validate, 350);
}

async function validate() {
  const yaml = el('yaml').value;
  try {
    accept(await api('/api/validate', { method: 'POST', body: yaml }), yaml);
  } catch (error) {
    setStatus('invalid', 'invalid');
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
    setStatus('invalid', 'not applied');
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
    setStatus('invalid', 'not applied');
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

const VIEWS = ['flow', 'catalogue', 'coverage'];

function showView(view) {
  const current = VIEWS.includes(view) ? view : 'flow';

  VIEWS.forEach((name) => {
    const pane = name === 'flow' ? el('flow-view') : el(name + '-view');
    const tab = el('view-' + name);
    pane.hidden = name !== current;
    tab.classList.toggle('is-current', name === current);
    tab.setAttribute('aria-selected', String(name === current));
  });

  // The mapping strip belongs to the mapping. NIEM coverage describes the model, which is the same
  // whichever mapping happens to be open, so leaving the strip up would imply a connection.
  document.querySelector('.flow-strip').hidden = current === 'coverage';

  if (current === 'catalogue') showCatalogue();
  if (current === 'coverage') showCoverage();
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

function accept(report, yaml) {
  if (report.svg) {
    apply(report.svg, report.mapping, report.problems);
  } else {
    // The mapping did not parse, so there is nothing new to draw. The previous picture stays: an
    // author fixing a mistake wants to see what they are fixing.
    showProblems(report.problems);
  }
  if (report.valid) {
    setStatus('valid', 'valid');
    el('save').disabled = yaml === state.savedYaml;
  } else {
    const count = report.problems.length;
    setStatus('invalid', `${count} problem${count === 1 ? '' : 's'}`);
    el('save').disabled = true;
  }
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
  showHop();
  showProblems(problems);
}

// --- the hop strip ----------------------------------------------------------

function wireDagNodes() {
  el('dag').querySelectorAll('.dag-node[data-hop]').forEach((node) => {
    const hopId = (node.dataset.hop || '').trim();
    node.classList.toggle('is-selected', hopId === state.hopId);
    node.addEventListener('click', () => {
      state.hopId = hopId;
      state.selected = null;
      showHop();
    });
  });
}

function renderColumns(columns) {
  const list = el('column-list');
  list.replaceChildren();
  (columns || []).forEach((column) => {
    const item = document.createElement('li');
    item.textContent = column;
    list.append(item);
  });
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
    el('stage-title').textContent = 'Field flow';
    el('canvas').replaceChildren();
    return;
  }
  el('stage-title').textContent = `${hop.id} → ${hop.entityType}`;
  canvas.render(hop.graph);
  canvas.select(state.selected);
  renderInspector();
  // The contract and the suggestions arrive after the drawing rather than blocking it. Both are
  // detail on demand; the flow is what the author came to look at.
  loadContract().then(renderInspector);
  loadSuggestions();
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
      container.append(hint('Nothing to propose — every field this step can fill is filled.'));
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
  })));

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
    setStatus('invalid', 'not applied');
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
function addStep(type) {
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
  name.setAttribute('aria-label', 'field this step writes');

  const commit = () => {
    const target = name.value.trim();
    if (!target) return;
    edit({ op: 'addStep', target, type, from: [] });
  };
  name.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') commit();
    if (event.key === 'Escape') renderInspector();
  });

  const actions = document.createElement('div');
  actions.className = 'inspector-actions';
  actions.append(action('Add step', commit), action('Cancel', renderInspector));

  panel.append(
    row('Writes', name),
    hint(`Added to the end of ${hop.id}. Wire its input on the canvas afterwards.`),
    suggestions(hop, name),
    actions,
  );
  name.focus();
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
    title.textContent = 'Nothing selected';
    panel.append(hint('Click a box on the canvas to see and change what it does.'));
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
  title.textContent = `${node.label} → ${step.target}`;

  panel.append(
    row('Writes', input(step.target, (value) =>
      edit({ op: 'setField', step: node.step, key: 'target', value }))),
    row('Transform', transformPicker(node.step, step.type)),
    row('Reads', hint(step.from.length ? step.from.join(', ') : 'nothing yet — wire an input')),
  );

  Object.entries(step.options || {}).forEach(([key, value]) => {
    panel.append(row(key, input(value, (next) =>
      edit({ op: 'setOption', step: node.step, key, value: next }))));
  });

  const actions = document.createElement('div');
  actions.className = 'inspector-actions';
  actions.append(
    action('Move earlier', () => edit({ op: 'moveStep', step: node.step, delta: -1 })),
    action('Move later', () => edit({ op: 'moveStep', step: node.step, delta: 1 })),
    action('Delete step', () => {
      state.selected = null;
      edit({ op: 'removeStep', step: node.step });
    }, 'danger'),
  );
  panel.append(actions);

  // Order is meaning here, not presentation: a step reads what earlier steps produced.
  panel.append(hint(`Step ${node.step + 1} of ${hop.steps.length}. Steps run in order.`));
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
  title.textContent = node.label;

  if (!state.contract) {
    panel.append(hint(describeKind(node)));
    panel.append(hint('No contract governs this step, so nothing checks this column.'));
    return;
  }

  const field = (state.contract.expects || []).find((entry) => entry.name === node.label);
  panel.append(hint(`Checked by ${state.contract.name}@${state.contract.version} on the way in.`));

  if (!field) {
    // The column is read but the inbound gate does not allow it: the record is rejected before the
    // step ever runs. One button fixes it.
    panel.append(hint('The contract does not declare this column, so the record would be rejected '
      + 'before this step runs.'));
    const actions = document.createElement('div');
    actions.className = 'inspector-actions';
    actions.append(action('Declare it', () =>
      editContract({ op: 'addField', field: node.label, type: 'string' })));
    panel.append(actions);
    return;
  }

  panel.append(row('Type', choice(
    ['string', 'integer', 'decimal', 'boolean', 'date', 'datetime'], field.type,
    (value) => editContract({ op: 'setAttribute', field: field.name, key: 'type', value }))));

  panel.append(row('Required', choice(['false', 'true'], String(field.required),
    (value) => editContract({ op: 'setAttribute', field: field.name, key: 'required', value }))));

  panel.append(row('Pattern', input(field.pattern, (value) =>
    editContract({ op: 'setAttribute', field: field.name, key: 'pattern', value }))));

  panel.append(hint(field.pattern
    ? 'A value that does not match is quarantined with a ContractViolation, rather than passed on.'
    : 'No pattern. A source that changes format would pass through unnoticed.'));

  const actions = document.createElement('div');
  actions.className = 'inspector-actions';
  actions.append(action('Remove from contract', () =>
    editContract({ op: 'removeField', field: field.name }), 'danger'));
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
    case 'column': return 'A column the source sends. Drag its right-hand dot onto a transform to feed it.';
    case 'scratch': return 'A working value. It is used by later steps and never reaches the record.';
    case 'field': return node.terminal
      ? 'A field of the record this step emits. This is the value the contract checks.'
      : 'An intermediate value. A later step overwrites it before the record is emitted.';
    case 'identity': return node.detail + '. This is what decides whether two records are the same thing.';
    case 'unbound': return 'Nothing produces this name, so the step reads nothing. Wire it to a column, or correct the name in the step that reads it.';
    case 'missing': return 'The contract requires this field and no step writes it. Deployed as it stands, every record would be quarantined. Add a step that writes it.';
    default: return '';
  }
}

function transformPicker(stepIndex, current) {
  const select = document.createElement('select');
  select.setAttribute('aria-label', 'transform');
  state.transforms.forEach((type) => {
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

function row(label, control) {
  const wrapper = document.createElement('label');
  wrapper.className = 'field';
  const name = document.createElement('span');
  name.textContent = label;
  wrapper.append(name, control);
  return wrapper;
}

function input(value, commit) {
  const field = document.createElement('input');
  field.type = 'text';
  field.value = value;
  // Committed on blur and on Enter, not per keystroke: each edit revalidates the whole mapping,
  // and half a field name is not worth validating.
  field.addEventListener('blur', () => {
    if (field.value !== value) commit(field.value);
  });
  field.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') field.blur();
    if (event.key === 'Escape') {
      field.value = value;
      field.blur();
    }
  });
  return field;
}

function action(label, onClick, kind) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'ghost' + (kind ? ` ${kind}` : '');
  button.textContent = label;
  button.addEventListener('click', onClick);
  return button;
}

function hint(text) {
  const paragraph = document.createElement('p');
  paragraph.className = 'detail';
  paragraph.textContent = text;
  return paragraph;
}

// --- problems and status ----------------------------------------------------

function showProblems(problems) {
  const container = el('problems');
  container.replaceChildren();

  if (!problems || problems.length === 0) {
    const ok = document.createElement('div');
    ok.className = 'problem problem--ok';
    ok.textContent = 'No problems. This mapping will load.';
    container.append(ok);
    return;
  }
  problems.forEach((problem) => {
    const item = document.createElement('div');
    item.className = 'problem';
    item.textContent = problem;
    container.append(item);
  });
}

function setStatus(kind, text) {
  const status = el('status');
  status.className = `status status--${kind}`;
  status.textContent = text;
}

// --- saving -----------------------------------------------------------------

async function save() {
  const yaml = el('yaml').value;
  try {
    const result = await api('/api/save', { method: 'POST', body: JSON.stringify({ yaml }) });
    if (!result.saved) {
      showProblems(result.problems);
      setStatus('invalid', 'not saved');
      return;
    }
    // A save writes a new version rather than replacing the file that was opened, so the mapping
    // that may already have been reviewed stays exactly as it was.
    state.savedYaml = yaml;
    state.file = result.file;
    el('save').disabled = true;
    setStatus('saved', `saved ${result.qualifiedName}`);
    await loadMappingList();
  } catch (error) {
    setStatus('invalid', 'not saved');
    showProblems([error.message]);
  }
}

// --- start ------------------------------------------------------------------

canvas = createCanvas(el('canvas'), { onSelect, onConnect, onDisconnect });

el('yaml').addEventListener('input', scheduleValidation);
el('save').addEventListener('click', save);
el('view-flow').addEventListener('click', () => showView('flow'));
el('view-catalogue').addEventListener('click', () => showView('catalogue'));
el('view-coverage').addEventListener('click', () => showView('coverage'));
el('relayout').addEventListener('click', () => canvas.reset());

(async function start() {
  await loadModule();
  // Read from the factory, so a transform added to the runtime appears here without anyone
  // remembering to update a list.
  state.transforms = (await api('/api/transforms')).types;
  renderPalette();
  await loadMappingList();
  const { mappings } = await api('/api/mappings');
  const first = mappings.find((mapping) => mapping.loadable);
  if (first) await openMapping(first.file);
})();
