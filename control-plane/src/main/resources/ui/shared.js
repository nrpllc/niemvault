/*
 * What the pipeline designer and the mapping editor have in common, written once.
 *
 * They were built apart and it showed: two status styles, two problem lists, two ways of saying the
 * same error. The rule now is that anything both editors show -- a status line, a problem, a button,
 * a hint -- comes from here, so an author who has learned one editor has learned how the other talks.
 */

export const el = (id) => document.getElementById(id);

export async function api(path, options) {
  const response = await fetch(path, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || 'Request failed');
  return payload;
}

export const post = (body) => ({
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

/**
 * A loader problem in the author's words.
 *
 * The loaders report where a problem is in their own terms -- "[UNKNOWN_TRANSFORM] draft
 * (hops[map-incident].steps[birthDate]): …" -- and the code in brackets is the loader's category, not
 * the author's: a misconfigured parseDate is filed under UNKNOWN_TRANSFORM although the transform is
 * perfectly well known. The location is what an author can act on, so that is kept and said plainly;
 * the category is dropped. Canonical types are named, not given as the URI they are keyed by.
 */
export function humanize(problem) {
  // Anywhere in the text, not only at its start: a problem is often quoted inside another message.
  const text = String(problem).replace(/\[[A-Z_]+\]\s+\S+\s+\(([^)]*)\):\s*/g, (all, location) => {
    const where = location
      .replace(/^hops\[([^\]]+)\]\.steps\[([^\]]+)\]$/, 'Hop $1 · step $2')
      .replace(/^hops\[([^\]]+)\]\.identity$/, 'Hop $1 · identity')
      .replace(/^hops\[([^\]]+)\]$/, 'Hop $1')
      .replace(/^decode\.columns$/, 'Source columns')
      .replace(/^decode$/, 'Decoding');
    return `${where}: `;
  });
  return text.replace(/https?:\/\/\S+?#([A-Za-z]+)/g, '$1');
}

/**
 * The one problem list. {@code ok} is said when there is nothing wrong, because an empty panel and
 * a panel that has not loaded look the same.
 */
export function renderProblems(container, problems, ok) {
  container.replaceChildren();
  container.classList.add('problems');
  if (!problems || problems.length === 0) {
    if (ok) {
      const item = document.createElement('div');
      item.className = 'problem problem--ok';
      item.textContent = ok;
      container.append(item);
    }
    return;
  }
  problems.forEach((problem) => {
    const item = document.createElement('div');
    item.className = 'problem';
    item.textContent = humanize(problem);
    container.append(item);
  });
}

/** A problem list as a detached element, for panels that build their content top to bottom. */
export function problems(list) {
  const container = document.createElement('div');
  renderProblems(container, list);
  return container;
}

/** The one status line: a sentence under the toolbar, in the same place in both editors. */
export function setStatusLine(element, kind, text) {
  element.className = `status-line is-${kind}`;
  element.textContent = text;
}

export function action(label, onClick, kind) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = label;
  if (kind) button.className = kind;
  button.addEventListener('click', onClick);
  return button;
}

export function hint(text) {
  const paragraph = document.createElement('p');
  paragraph.className = 'detail';
  paragraph.textContent = text;
  return paragraph;
}

export function field(label, control) {
  const wrapper = document.createElement('label');
  wrapper.className = 'field';
  const span = document.createElement('span');
  span.textContent = label;
  wrapper.append(span, control);
  return wrapper;
}

/**
 * The steps an author reaches for first, in that order; the rest follow alphabetically. Only names
 * the transform factory actually has are shown -- this orders the factory's list, it adds nothing.
 */
const COMMON = ['copy', 'trim', 'upper', 'parseDate', 'parseDateTime', 'codeMap', 'splitIndex', 'concat', 'nullIf'];

export function orderTransforms(types) {
  const common = COMMON.filter((type) => types.includes(type));
  const rest = types.filter((type) => !common.includes(type)).sort();
  return { common, rest };
}
