/*
 * Mapping authoring surface.
 *
 * Vanilla JS on purpose: no framework, no build step, nothing fetched at runtime. Spec section 6
 * makes air-gapped delivery mandatory, and every dependency here is one an agency has to mirror.
 *
 * This file decides nothing about whether a mapping is valid, and it never rewrites YAML itself.
 * Both jobs belong to the server, which asks the same loaders the runtime uses and patches the
 * text line by line. That is the whole point of ADR 0021 -- an editor with its own opinion about
 * validity would eventually disagree with the engine, and one that regenerated YAML would erase
 * the commentary explaining why each step exists.
 */

const el = (id) => document.getElementById(id);

const state = {
  file: null,
  savedYaml: '',
  mapping: null,
  transforms: [],
  selectedHop: null,
  debounce: null,
};

async function api(path, options) {
  const response = await fetch(path, options);
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.error || 'Request failed');
  }
  return payload;
}

// --- module and mapping list -----------------------------------------------

async function loadModule() {
  try {
    const module = await api('/api/module');
    el('module-name').textContent = `${module.displayName} · ${module.name}@${module.version}`;
    el('module-detail').textContent =
      `platform ${module.platformVersions} · canonical model ${module.canonicalModel}` +
      (module.steward ? ` · steward ${module.steward}` : '');
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
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    // A mapping that will not load is still listed. Hiding it would leave an author unable to
    // open the one file they need to fix.
    button.className = mapping.loadable ? '' : 'broken';
    button.setAttribute('aria-current', String(mapping.file === state.file));

    const name = document.createElement('span');
    name.textContent = mapping.name;
    const version = document.createElement('span');
    version.className = 'version';
    version.textContent = mapping.loadable ? mapping.version : 'does not load';

    button.append(name, version);
    button.addEventListener('click', () => openMapping(mapping.file));
    item.append(button);
    list.append(item);
  });
}

async function openMapping(file) {
  try {
    const mapping = await api(`/api/mapping?file=${encodeURIComponent(file)}`);
    state.file = file;
    state.savedYaml = mapping.source;
    el('yaml').value = mapping.source;
    render(mapping.svg, mapping, []);
    setStatus('valid', `${mapping.name}@${mapping.version}`);
    await loadMappingList();
  } catch (error) {
    setStatus('invalid', 'cannot open');
    showProblems([error.message]);
  }
}

// --- validation -------------------------------------------------------------

function scheduleValidation() {
  clearTimeout(state.debounce);
  setStatus('idle', 'checking…');
  // Debounced rather than per-keystroke: validation parses the whole document, and a half-typed
  // line is not worth reporting on.
  state.debounce = setTimeout(validate, 350);
}

async function validate() {
  const yaml = el('yaml').value;
  try {
    const report = await api('/api/validate', { method: 'POST', body: yaml });
    applyReport(report, yaml);
  } catch (error) {
    setStatus('invalid', 'invalid');
    showProblems([error.message]);
    el('save').disabled = true;
  }
}

function applyReport(report, yaml) {
  if (report.svg) {
    render(report.svg, report.mapping, report.problems);
  } else {
    // The mapping did not parse, so there is nothing new to draw. The previous graph stays on
    // screen rather than blanking: an author fixing a typo wants to see what they are fixing.
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

// --- structural edits -------------------------------------------------------

/*
 * Every form control routes here. The server owns the edit: it patches the text, revalidates, and
 * sends back both. Nothing is applied locally and hoped to match.
 */
async function edit(request) {
  try {
    const report = await api('/api/edit', {
      method: 'POST',
      body: JSON.stringify({ yaml: el('yaml').value, ...request }),
    });
    el('yaml').value = report.yaml;
    applyReport(report, report.yaml);
  } catch (error) {
    // An edit the text patcher will not make -- an option that spans lines, a step that cannot
    // move any further. Say so and change nothing.
    showProblems([error.message]);
    setStatus('invalid', 'not applied');
  }
}

// --- rendering --------------------------------------------------------------

function render(svg, mapping, problems) {
  state.mapping = mapping;
  el('dag').innerHTML = svg;
  wireDagNodes();
  renderColumns(mapping.columns);
  renderHops(mapping.hops);
  showProblems(problems);
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

/** Clicking a hop in the graph brings up the steps that produce it. */
function wireDagNodes() {
  el('dag').querySelectorAll('.dag-node--hop').forEach((node) => {
    const hopId = (node.dataset.hop || '').trim();
    if (!hopId) {
      return;
    }
    node.classList.toggle('is-selected', hopId === state.selectedHop);
    node.addEventListener('click', () => selectHop(hopId));
  });
}

function selectHop(hopId) {
  state.selectedHop = hopId;
  el('dag').querySelectorAll('.dag-node--hop').forEach((node) => {
    node.classList.toggle('is-selected', (node.dataset.hop || '').trim() === hopId);
  });
  document.querySelectorAll('.hop').forEach((card) => {
    card.classList.toggle('is-selected', card.dataset.hop === hopId);
  });
  const card = document.querySelector(`.hop[data-hop="${CSS.escape(hopId)}"]`);
  if (card) {
    // Aligned to the top, not merely brought into view: a hop card is taller than the panel is
    // likely to be, and the minimal scroll leaves its heading and first steps above the fold.
    //
    // Instant, not smooth. Smooth scrolling is silently ignored wherever the user or the platform
    // has asked for reduced motion, and a jump that sometimes does not happen is worse than one
    // that never animates.
    card.scrollIntoView({ block: 'start' });
  }
}

function renderHops(hops) {
  const container = el('hops');
  container.replaceChildren();

  (hops || []).forEach((hop) => {
    const card = document.createElement('div');
    card.className = 'hop' + (hop.id === state.selectedHop ? ' is-selected' : '');
    card.dataset.hop = hop.id;

    const heading = document.createElement('h3');
    heading.textContent = `${hop.id} → ${hop.entityType}`;

    const meta = document.createElement('p');
    meta.className = 'meta';
    const identity = hop.identityMode === 'RESOLVE'
      ? `identity resolved by ${hop.identityDetail}`
      : `identity derived from ${hop.identityDetail}`;
    meta.textContent = `${hop.contract} · ${identity}`
      + (hop.dependsOn.length ? ` · after ${hop.dependsOn.join(', ')}` : '');

    const steps = document.createElement('ol');
    steps.className = 'steps';
    hop.steps.forEach((step, index) => {
      steps.append(stepRow(hop, step, index));
    });

    const add = document.createElement('button');
    add.type = 'button';
    add.className = 'ghost add-step';
    add.textContent = '+ Add step';
    add.addEventListener('click', () => addStep(hop));

    card.append(heading, meta, steps, add);
    card.addEventListener('click', () => selectHop(hop.id));
    container.append(card);
  });
}

function stepRow(hop, step, index) {
  const row = document.createElement('li');
  // Scratch targets never reach the output record, so they are shown as what they are: working
  // values, not fields of the canonical type.
  row.className = 'step' + (step.scratch ? ' scratch' : '');

  const target = field('text', step.target, (value) =>
    edit({ op: 'setField', hop: hop.id, step: index, key: 'target', value }));
  target.classList.add('target');
  target.setAttribute('aria-label', 'target field');

  const type = document.createElement('select');
  type.className = 'type';
  type.setAttribute('aria-label', 'transform');
  state.transforms.forEach((name) => {
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    option.selected = name === step.type;
    type.append(option);
  });
  if (!state.transforms.includes(step.type)) {
    // A type the factory does not know still has to be shown, or the row would silently claim
    // the mapping says something it does not.
    const unknown = document.createElement('option');
    unknown.value = step.type;
    unknown.textContent = step.type + ' (unknown)';
    unknown.selected = true;
    type.prepend(unknown);
  }
  type.addEventListener('change', () =>
    edit({ op: 'setField', hop: hop.id, step: index, key: 'type', value: type.value }));

  const from = field('text', step.from.join(', '), (value) =>
    edit({ op: 'setFrom', hop: hop.id, step: index, from: value.split(',') }));
  from.classList.add('from');
  from.placeholder = 'inputs';
  from.setAttribute('aria-label', 'inputs');

  const options = document.createElement('div');
  options.className = 'options';
  Object.entries(step.options || {}).forEach(([key, value]) => {
    const label = document.createElement('label');
    label.className = 'option';
    const name = document.createElement('span');
    name.textContent = key;
    const input = field('text', value, (next) =>
      edit({ op: 'setOption', hop: hop.id, step: index, key, value: next }));
    label.append(name, input);
    options.append(label);
  });

  row.append(target, type, from, rowActions(hop, index), options);
  return row;
}

function rowActions(hop, index) {
  const actions = document.createElement('div');
  actions.className = 'row-actions';

  // Order is meaning in this DSL -- a step reads what earlier steps produced -- so moving one is
  // a real edit, not a presentational nicety.
  actions.append(
    iconButton('↑', 'move earlier', () =>
      edit({ op: 'moveStep', hop: hop.id, step: index, delta: -1 })),
    iconButton('↓', 'move later', () =>
      edit({ op: 'moveStep', hop: hop.id, step: index, delta: 1 })),
    iconButton('✕', 'remove step', () =>
      edit({ op: 'removeStep', hop: hop.id, step: index })),
  );
  return actions;
}

function addStep(hop) {
  const target = window.prompt(`New step in ${hop.id} — target field?`);
  if (!target) {
    return;
  }
  edit({ op: 'addStep', hop: hop.id, target, type: 'copy', from: [] });
}

function field(type, value, commit) {
  const input = document.createElement('input');
  input.type = type;
  input.value = value;
  // Committed on blur and on Enter, not per keystroke: each edit is a round trip that revalidates
  // the whole mapping, and half a field name is not worth validating.
  input.addEventListener('blur', () => {
    if (input.value !== value) {
      commit(input.value);
    }
  });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      input.blur();
    } else if (event.key === 'Escape') {
      input.value = value;
      input.blur();
    }
  });
  return input;
}

function iconButton(glyph, label, onClick) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'icon';
  button.textContent = glyph;
  button.title = label;
  button.setAttribute('aria-label', label);
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    onClick();
  });
  return button;
}

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
    const result = await api('/api/save', {
      method: 'POST',
      body: JSON.stringify({ yaml }),
    });
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

el('yaml').addEventListener('input', scheduleValidation);
el('save').addEventListener('click', save);

(async function start() {
  await loadModule();
  // Read from the factory, so a transform added to the runtime appears in the dropdown without
  // anyone remembering to update a list here.
  state.transforms = (await api('/api/transforms')).types;
  await loadMappingList();
  const { mappings } = await api('/api/mappings');
  const first = mappings.find((mapping) => mapping.loadable);
  if (first) {
    await openMapping(first.file);
  }
})();
