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
  } catch (error) {
    // An edit the text patcher will not make -- an option that spans lines, a step that cannot move
    // any further. Say so and change nothing.
    showProblems([error.message]);
    setStatus('invalid', 'not applied');
  }
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

function addStep(type) {
  const hop = currentHop();
  if (!hop) return;
  const target = window.prompt(`New ${type} step in ${hop.id}. Which field does it write?`);
  if (!target) return;
  edit({ op: 'addStep', target, type, from: [] });
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

function describeKind(node) {
  switch (node.kind) {
    case 'column': return 'A column the source sends. Drag its right-hand dot onto a transform to feed it.';
    case 'scratch': return 'A working value. It is used by later steps and never reaches the record.';
    case 'field': return node.terminal
      ? 'A field of the record this step emits. This is the value the contract checks.'
      : 'An intermediate value. A later step overwrites it before the record is emitted.';
    case 'identity': return node.detail + '. This is what decides whether two records are the same thing.';
    case 'unbound': return 'Nothing produces this name, so the step reads nothing. Wire it to a column, or correct the name in the step that reads it.';
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
