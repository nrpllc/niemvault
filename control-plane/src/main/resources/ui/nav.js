/*
 * Where the author is, kept in the URL.
 *
 * One path through the surface -- Pipeline › Stage › Hop › Step -- and every part of it a query
 * parameter, so reload returns to the same place, back and forward walk the way the author came, and
 * a link can be sent. Before this the address never changed: a reload landed on whichever mapping
 * loaded first, and the pipeline an author had drilled in from was simply gone.
 *
 * Names, never file paths: a link says which pipeline or mapping it means, not where on this
 * filesystem the artifact happens to sit.
 */

const KEYS = ['view', 'pipeline', 'stage', 'mapping', 'hop', 'step', 'draft'];

export function read() {
  const params = new URLSearchParams(location.search);
  const state = {};
  KEYS.forEach((key) => {
    const value = params.get(key);
    if (value !== null && value !== '') state[key] = value;
  });
  return state;
}

function write(state) {
  const params = new URLSearchParams();
  KEYS.forEach((key) => {
    if (state[key] !== undefined && state[key] !== null && state[key] !== '') params.set(key, state[key]);
  });
  const query = params.toString();
  return `${location.pathname}${query ? `?${query}` : ''}`;
}

let quiet = false;

/**
 * Records a move. {@code replace} for refinements of the same place (selecting a step), a new
 * history entry for going somewhere (opening a pipeline, drilling into a mapping).
 */
export function go(changes, { replace = false } = {}) {
  if (quiet) return;
  const next = { ...read(), ...changes };
  Object.keys(changes).forEach((key) => { if (changes[key] === null) delete next[key]; });
  const url = write(next);
  if (url === `${location.pathname}${location.search}`) return;
  if (replace) history.replaceState(next, '', url);
  else history.pushState(next, '', url);
}

/** Runs {@code fn} without recording moves: for restoring a place, not going to one. */
export async function restoring(fn) {
  quiet = true;
  try {
    return await fn();
  } finally {
    quiet = false;
  }
}

export function onPop(handler) {
  window.addEventListener('popstate', () => handler(read()));
}
