/*
 * The flow canvas.
 *
 * A hop drawn as the data flow it is -- source columns in, transforms, fields out -- and edited by
 * pointing at it. Nobody administering this platform at an agency should have to read YAML to
 * change how a field is mapped.
 *
 * Hand-rolled SVG and pointer events, no diagramming library. Spec section 6 makes air-gapped
 * delivery mandatory, and every dependency is one an agency has to mirror and keep patched.
 *
 * Layout is computed, never stored. Node coordinates are not part of what a mapping means, and a
 * mapping is a versioned artifact an auditor reads -- two mappings that behave identically must not
 * differ on disk because somebody dragged a box. Dragging moves a node for the session only.
 */

const NODE_W = 168;
const NODE_H = 54;
const COL_GAP = 96;
const ROW_GAP = 22;
const PAD = 28;

const KIND_ORDER = { column: 0, unbound: 0, transform: 1, scratch: 2, field: 2, identity: 3 };

export function createCanvas(root, handlers) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'canvas');
  root.replaceChildren(svg);

  const state = {
    graph: null,
    positions: new Map(),   // node id -> {x, y}, session-only
    moved: new Set(),       // ids the user has dragged; auto-layout leaves these alone
    selected: null,
    drag: null,
    wire: null,
    nodeEls: new Map(),     // node id -> <g>, so a drag moves an element instead of rebuilding
    edgeEls: [],            // {edge, path, hit}, likewise
  };

  // --- layout ---------------------------------------------------------------

  /*
   * Layered by longest path from an input. Depth, not insertion order: a step that reads what
   * another step produced must sit to its right, or the arrows cross backwards and the picture
   * stops reading as a flow.
   */
  function layout(graph) {
    const incoming = new Map(graph.nodes.map((n) => [n.id, []]));
    graph.edges.forEach((e) => incoming.get(e.to)?.push(e.from));

    const depth = new Map();
    const resolve = (id, seen = new Set()) => {
      if (depth.has(id)) return depth.get(id);
      if (seen.has(id)) return 0;           // defensive: the server guarantees acyclic
      seen.add(id);
      const parents = incoming.get(id) || [];
      const d = parents.length === 0 ? 0 : Math.max(...parents.map((p) => resolve(p, seen))) + 1;
      depth.set(id, d);
      return d;
    };
    graph.nodes.forEach((n) => resolve(n.id));

    // Identity always sits last, however shallow its inputs happen to be.
    const maxDepth = Math.max(0, ...graph.nodes.map((n) => depth.get(n.id)));
    graph.nodes.forEach((n) => {
      if (n.kind === 'identity') depth.set(n.id, maxDepth);
    });

    const columns = new Map();
    graph.nodes
      .slice()
      .sort((a, b) => (KIND_ORDER[a.kind] ?? 9) - (KIND_ORDER[b.kind] ?? 9))
      .forEach((n) => {
        const d = depth.get(n.id);
        if (!columns.has(d)) columns.set(d, []);
        columns.get(d).push(n);
      });

    const placed = new Map();
    [...columns.keys()].sort((a, b) => a - b).forEach((d) => {
      columns.get(d).forEach((n, row) => {
        placed.set(n.id, {
          x: PAD + d * (NODE_W + COL_GAP),
          y: PAD + row * (NODE_H + ROW_GAP),
        });
      });
    });
    return placed;
  }

  function positionOf(id) {
    return state.positions.get(id) || { x: 0, y: 0 };
  }

  // --- drawing --------------------------------------------------------------

  function draw() {
    const graph = state.graph;
    if (!graph) return;

    const width = Math.max(...[...state.positions.values()].map((p) => p.x + NODE_W)) + PAD;
    const height = Math.max(...[...state.positions.values()].map((p) => p.y + NODE_H)) + PAD;
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('width', width);
    svg.setAttribute('height', height);
    svg.replaceChildren(defs());
    state.nodeEls.clear();
    state.edgeEls = [];

    graph.edges.forEach((edge) => svg.append(drawEdge(edge)));
    graph.nodes.forEach((node) => svg.append(drawNode(node)));
    if (state.wire) svg.append(state.wire.line);
  }

  /*
   * Moves one node and re-paths only the edges touching it.
   *
   * Rebuilding the whole drawing on every pointermove destroys the element currently holding
   * pointer capture, which loses the gesture, and on a large flow it is enough DOM churn to stall
   * the frame. Dragging touches what it moves and nothing else.
   */
  function moveNode(id) {
    const group = state.nodeEls.get(id);
    if (!group) return;
    const at = positionOf(id);
    group.setAttribute('transform', `translate(${at.x} ${at.y})`);

    state.edgeEls.forEach(({ edge, path, hit }) => {
      if (edge.from !== id && edge.to !== id) return;
      const d = edgePath(edge);
      path.setAttribute('d', d);
      hit.setAttribute('d', d);
    });
  }

  function edgePath(edge) {
    const from = positionOf(edge.from);
    const to = positionOf(edge.to);
    const x1 = from.x + NODE_W;
    const y1 = from.y + NODE_H / 2;
    const x2 = to.x;
    const y2 = to.y + NODE_H / 2;
    const bend = Math.max(24, (x2 - x1) / 2);
    return `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`;
  }

  function defs() {
    const el = svgEl('defs');
    el.innerHTML =
      '<marker id="tip" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6"' +
      ' orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" class="canvas-arrow"/></marker>';
    return el;
  }

  function drawEdge(edge) {
    const group = svgEl('g');
    group.setAttribute('class', 'canvas-edge');

    // A wide transparent stroke under the visible one: a 1px line is close to impossible to click.
    const d = edgePath(edge);
    const hit = svgEl('path');
    hit.setAttribute('d', d);
    hit.setAttribute('class', 'canvas-edge-hit');

    const line = svgEl('path');
    line.setAttribute('d', d);
    line.setAttribute('class', 'canvas-edge-line');
    line.setAttribute('marker-end', 'url(#tip)');

    group.append(hit, line);
    state.edgeEls.push({ edge, path: line, hit });
    if (edge.label && edge.to.startsWith('step:')) {
      // Only inbound edges are detachable, so only they offer the affordance.
      group.classList.add('is-detachable');
      group.setAttribute('tabindex', '0');
      group.setAttribute('role', 'button');
      group.setAttribute('aria-label', `disconnect ${edge.label}`);
      const detach = () => handlers.onDisconnect(Number(edge.to.slice(5)), edge.label);
      group.addEventListener('click', detach);
      group.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          detach();
        }
      });
    }
    return group;
  }

  function drawNode(node) {
    const at = positionOf(node.id);
    const group = svgEl('g');
    group.setAttribute('class',
      `canvas-node canvas-node--${node.kind}`
      + (node.terminal ? ' is-terminal' : '')
      + (state.selected === node.id ? ' is-selected' : ''));
    group.setAttribute('transform', `translate(${at.x} ${at.y})`);
    group.dataset.node = node.id;

    const box = svgEl('rect');
    box.setAttribute('width', NODE_W);
    box.setAttribute('height', NODE_H);
    box.setAttribute('rx', node.kind === 'transform' ? 3 : 14);
    group.append(box);

    group.append(text(node.label, 12, 22, 'canvas-label'));
    if (node.detail) group.append(text(node.detail, 12, 39, 'canvas-detail'));

    // Ports. Out on every node that can feed something, in on transforms only -- values are
    // written by exactly one step, so an inbound port on a value would promise an edit that the
    // DSL cannot express.
    if (node.kind !== 'identity') {
      group.append(port(NODE_W, NODE_H / 2, 'out', node.id));
    }
    if (node.kind === 'transform') {
      group.append(port(0, NODE_H / 2, 'in', node.id));
    }

    group.addEventListener('pointerdown', (event) => beginDrag(event, node, group));
    state.nodeEls.set(node.id, group);
    return group;
  }

  function port(x, y, side, nodeId) {
    const dot = svgEl('circle');
    dot.setAttribute('cx', x);
    dot.setAttribute('cy', y);
    dot.setAttribute('r', 5);
    dot.setAttribute('class', `canvas-port canvas-port--${side}`);
    dot.dataset.port = side;
    dot.dataset.node = nodeId;
    return dot;
  }

  function text(value, x, y, className) {
    const el = svgEl('text');
    el.setAttribute('x', x);
    el.setAttribute('y', y);
    el.setAttribute('class', className);
    el.textContent = value.length > 22 ? value.slice(0, 21) + '…' : value;
    if (value.length > 22) {
      const title = svgEl('title');
      title.textContent = value;
      el.append(title);
    }
    return el;
  }

  function svgEl(name) {
    return document.createElementNS('http://www.w3.org/2000/svg', name);
  }

  // --- pointer ---------------------------------------------------------------

  function toCanvas(event) {
    const box = svg.getBoundingClientRect();
    const view = svg.viewBox.baseVal;
    return {
      x: ((event.clientX - box.left) / box.width) * view.width,
      y: ((event.clientY - box.top) / box.height) * view.height,
    };
  }

  function beginDrag(event, node, group) {
    const port = event.target.dataset && event.target.dataset.port;
    if (port === 'out') {
      beginWire(event, node);
      return;
    }
    if (port === 'in') return;

    // Deliberately no preventDefault here. A press is not yet a drag, and suppressing the default
    // on every press swallows focus and the compatibility mouse events for what is usually just a
    // selection. Text selection during an actual drag is handled in CSS instead.
    //
    // Selected on press, not on click: a click event after a drag is unreliable, and selecting
    // immediately is what makes the inspector feel attached to the thing under the pointer.
    handlers.onSelect(node);

    const start = toCanvas(event);
    const origin = positionOf(node.id);
    // Armed, not dragging. The pointer is captured on the first real movement, so a plain click
    // stays a plain click: capturing on press would make every selection a zero-length drag and
    // leave the page holding a pointer it never meant to take.
    state.drag = {
      id: node.id,
      group,
      pointerId: event.pointerId,
      dx: start.x - origin.x,
      dy: start.y - origin.y,
      from: start,
      active: false,
    };
  }

  /** Movement past this many canvas units is a drag; anything less is a click. */
  const DRAG_THRESHOLD = 3;

  function beginWire(event, node) {
    event.preventDefault();
    event.stopPropagation();
    const line = svgEl('path');
    line.setAttribute('class', 'canvas-wire');
    state.wire = { from: node, line };
    svg.setPointerCapture(event.pointerId);
    svg.append(line);
  }

  svg.addEventListener('pointermove', (event) => {
    if (state.drag) {
      const at = toCanvas(event);
      if (!state.drag.active) {
        const moved = Math.hypot(at.x - state.drag.from.x, at.y - state.drag.from.y);
        if (moved < DRAG_THRESHOLD) return;
        state.drag.active = true;
        event.preventDefault();
        try {
          state.drag.group.setPointerCapture(state.drag.pointerId);
        } catch (unavailable) {
          // Some input sources do not offer a capturable pointer. Dragging still works from the
          // canvas-level listeners; it just stops if the pointer leaves the surface.
        }
      }
      state.positions.set(state.drag.id, { x: at.x - state.drag.dx, y: at.y - state.drag.dy });
      state.moved.add(state.drag.id);
      moveNode(state.drag.id);
      return;
    }
    if (state.wire) {
      const from = positionOf(state.wire.from.id);
      const at = toCanvas(event);
      state.wire.line.setAttribute('d',
        `M ${from.x + NODE_W} ${from.y + NODE_H / 2} L ${at.x} ${at.y}`);
    }
  });

  svg.addEventListener('pointerup', (event) => {
    if (state.drag) {
      const { group, pointerId, active } = state.drag;
      state.drag = null;
      if (active && group.hasPointerCapture && group.hasPointerCapture(pointerId)) {
        group.releasePointerCapture(pointerId);
      }
      return;
    }
    if (!state.wire) return;

    if (svg.hasPointerCapture && svg.hasPointerCapture(event.pointerId)) {
      svg.releasePointerCapture(event.pointerId);
    }
    const target = document.elementFromPoint(event.clientX, event.clientY);
    const group = target && target.closest ? target.closest('.canvas-node--transform') : null;
    const wire = state.wire;
    state.wire = null;
    draw();

    if (group) {
      const step = Number(group.dataset.node.slice(5));
      handlers.onConnect(step, wire.from);
    }
  });

  // --- api ------------------------------------------------------------------

  return {
    render(graph) {
      state.graph = graph;
      const fresh = layout(graph);
      // Recomputed on every change, except where the user has put something. Their arrangement is
      // an intent about this session; the layout algorithm has no business overruling it.
      fresh.forEach((at, id) => {
        if (!state.moved.has(id)) state.positions.set(id, at);
      });
      [...state.positions.keys()].forEach((id) => {
        if (!fresh.has(id)) {
          state.positions.delete(id);
          state.moved.delete(id);
        }
      });
      draw();
    },
    select(id) {
      // Classes only, never a rebuild. Selection happens on pointerdown, and rebuilding the
      // drawing there would detach the very element the gesture has hold of -- the drag would be
      // lost and setPointerCapture would throw on a node no longer in the document.
      state.selected = id;
      state.nodeEls.forEach((group, nodeId) =>
        group.classList.toggle('is-selected', nodeId === id));
    },
    reset() {
      state.moved.clear();
      if (state.graph) this.render(state.graph);
    },
  };
}
