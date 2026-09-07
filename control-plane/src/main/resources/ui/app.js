/*
 * Mapping authoring surface.
 *
 * Vanilla JS on purpose: no framework, no build step, nothing fetched at runtime. Spec section 6
 * makes air-gapped delivery mandatory, and every dependency here is one an agency has to mirror.
 *
 * This file decides nothing about whether a mapping is valid. It sends the draft to the server,
 * which asks the same loaders the runtime uses. That is the whole point of ADR 0021 -- an editor
 * with its own opinion about validity would eventually disagree with the engine.
 */

const el = (id) => document.getElementById(id);

const state = {
  file: null,
  savedYaml: '',
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
    if (report.svg) {
      render(report.svg, report.mapping, report.problems);
    } else {
      showProblems(report.problems);
    }
    if (report.valid) {
      setStatus('valid', 'valid');
      el('save').disabled = yaml === state.savedYaml;
    } else {
      setStatus('invalid', `${report.problems.length} problem${report.problems.length === 1 ? '' : 's'}`);
      el('save').disabled = true;
    }
  } catch (error) {
    setStatus('invalid', 'invalid');
    showProblems([error.message]);
    el('save').disabled = true;
  }
}

// --- rendering --------------------------------------------------------------

function render(svg, mapping, problems) {
  el('dag').innerHTML = svg;
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

function renderHops(hops) {
  const container = el('hops');
  container.replaceChildren();

  (hops || []).forEach((hop) => {
    const card = document.createElement('div');
    card.className = 'hop';

    const heading = document.createElement('h3');
    heading.textContent = `${hop.id} → ${hop.entityType}`;

    const meta = document.createElement('p');
    meta.className = 'meta';
    const identity = hop.identityMode === 'RESOLVE'
      ? `identity resolved by ${hop.identityDetail}`
      : `identity derived from ${hop.identityDetail}`;
    meta.textContent = `${hop.contract} · ${identity}`
      + (hop.dependsOn.length ? ` · after ${hop.dependsOn.join(', ')}` : '');

    const table = document.createElement('table');
    table.className = 'steps';
    hop.steps.forEach((step) => {
      const row = document.createElement('tr');
      if (step.scratch) {
        // Scratch targets never reach the output record, so they are shown as what they are:
        // working values, not fields of the canonical type.
        row.className = 'scratch';
      }
      row.append(
        cell('target', step.target),
        cell('type', step.type),
        cell('from', step.from.length ? `← ${step.from.join(', ')}` : ''),
      );
      table.append(row);
    });

    card.append(heading, meta, table);
    container.append(card);
  });
}

function cell(className, text) {
  const td = document.createElement('td');
  td.className = className;
  td.textContent = text;
  return td;
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
  await loadMappingList();
  const { mappings } = await api('/api/mappings');
  const first = mappings.find((mapping) => mapping.loadable);
  if (first) {
    await openMapping(first.file);
  }
})();
