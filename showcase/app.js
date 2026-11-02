(() => {
  'use strict';

  const AUTO_ADVANCE_MS = 7600;
  const stage = document.querySelector('#stage');
  const sceneCounter = document.querySelector('#sceneCounter');
  const sceneLabel = document.querySelector('#sceneLabel');
  const progressFill = document.querySelector('#progressFill');
  const progressTrack = document.querySelector('.progress-track');
  const progressTime = document.querySelector('#progressTime');
  const sceneDots = document.querySelector('#sceneDots');
  const playButton = document.querySelector('#playButton');
  const playIcon = playButton.querySelector('.play-icon');
  const previousButton = document.querySelector('#previousButton');
  const nextButton = document.querySelector('#nextButton');
  const srStatus = document.querySelector('#srStatus');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const state = {
    scenes: [],
    index: 0,
    playing: !reduceMotion,
    timer: null,
    startedAt: 0,
    elapsedBeforePause: 0
  };

  const escapeHtml = (value) => String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');

  const list = (items, renderer) => items.map(renderer).join('');

  function renderScene(scene) {
    const common = `
      <div class="scene-heading">
        <p class="scene-tag"><span class="tag-line"></span>${escapeHtml(scene.tag)}</p>
        <h1>${escapeHtml(scene.title)}</h1>
        <p class="scene-dek">${escapeHtml(scene.dek)}</p>
      </div>`;

    switch (scene.type) {
      case 'hero':
        return `<div class="scene-layout hero-layout">
          <div class="hero-copy">${common}
            <p class="hero-note"><span class="note-mark">↳</span>${escapeHtml(scene.note)}</p>
            <div class="stat-row">${list(scene.stats, (stat) => `<div class="stat"><span>${escapeHtml(stat.label)}</span><strong>${escapeHtml(stat.value)}</strong></div>`)}</div>
          </div>
          <div class="hero-visual" aria-label="Layered backend system diagram">
            <div class="visual-orbit orbit-a"></div><div class="visual-orbit orbit-b"></div>
            <div class="system-core"><span class="core-pulse"></span><span class="core-label">ROOM<br>PROTOCOL</span><small>ACTIVE SLICE</small></div>
            <div class="layer-stack">${list(scene.visual.layers, (layer, index) => `<div class="stack-card stack-${index + 1}"><span>0${index + 1}</span>${escapeHtml(layer)}</div>`)}</div>
            <div class="code-window"><div class="window-bar"><i></i><i></i><i></i><span>${escapeHtml(scene.visual.label)}</span></div>${list(scene.visual.code, (line) => `<code>${escapeHtml(line)}</code>`)}</div>
          </div>
        </div>`;

      case 'problem':
        return `<div class="scene-layout problem-layout">${common}
          <div class="problem-grid">${list(scene.cards, (card) => `<article class="problem-card"><span class="card-index">${escapeHtml(card.index)}</span><h2>${escapeHtml(card.title)}</h2><p>${escapeHtml(card.text)}</p><span class="card-arrow">↗</span></article>`)}</div>
          <div class="scene-callout"><span class="callout-mark">◈</span>${escapeHtml(scene.callout)}</div>
        </div>`;

      case 'architecture':
        return `<div class="scene-layout architecture-layout">${common}
          <div class="architecture-map">
            <div class="map-flow">${list(scene.flow, (item, index) => `<span class="flow-node"><b>0${index + 1}</b>${escapeHtml(item)}</span>${index < scene.flow.length - 1 ? '<span class="flow-arrow">→</span>' : ''}`)}</div>
            <div class="layer-list">${list(scene.layers, (layer) => `<div class="arch-layer arch-${escapeHtml(layer.accent)}"><span class="layer-signal"></span><div><strong>${escapeHtml(layer.name)}</strong><p>${escapeHtml(layer.detail)}</p></div><span class="layer-chevron">›</span></div>`)}</div>
            <p class="map-footer"><span>⌁</span>${escapeHtml(scene.footer)}</p>
          </div>
        </div>`;

      case 'auth':
        return `<div class="scene-layout auth-layout">${common}
          <div class="auth-stage">
            <div class="auth-path">${list(scene.steps, (step, index) => `<div class="auth-step"><span class="step-number">${escapeHtml(step.number)}</span><div class="step-copy"><strong>${escapeHtml(step.title)}</strong><p>${escapeHtml(step.detail)}</p></div></div>${index < scene.steps.length - 1 ? '<div class="path-line"></div>' : ''}`)}</div>
            <div class="guardrail"><div class="guardrail-header"><span>SERVER GUARDRAILS</span><span class="lock-icon">⌑</span></div>${list(scene.guards, (guard) => `<p><span class="check">✓</span>${escapeHtml(guard)}</p>`)}</div>
          </div>
        </div>`;

      case 'lifecycle':
        return `<div class="scene-layout lifecycle-layout">${common}
          <div class="lifecycle-body">
            <div class="lifecycle-track">${list(scene.phases, (phase, index) => `<div class="phase"><span class="phase-dot">${index === scene.phases.length - 1 ? '×' : index + 1}</span><strong>${escapeHtml(phase.name)}</strong><p>${escapeHtml(phase.detail)}</p></div>`)}</div>
            <div class="side-actions">${list(scene.sideActions, (action) => `<div class="side-action"><span>${escapeHtml(action.label)}</span><strong>${escapeHtml(action.value)}</strong></div>`)}</div>
            <div class="scene-callout"><span class="callout-mark">◈</span>${escapeHtml(scene.callout)}</div>
          </div>
        </div>`;

      case 'events':
        return `<div class="scene-layout events-layout">${common}
          <div class="events-body">
            <div class="websocket-window"><div class="window-bar"><i></i><i></i><i></i><span>AUTHENTICATED CHANNEL / LIVE</span></div><div class="ws-url"><span class="ws-method">WS</span><code>/api/v1/ws/watch-spaces/{id}</code></div><div class="event-chips">${list(scene.events, (event) => `<span class="event-chip ${event.kind === 'host only' ? 'host-event' : ''}"><b>${escapeHtml(event.name)}</b><small>${escapeHtml(event.kind)}</small></span>`)}</div><div class="json-line"><span>{</span> <em>"type"</em>: <strong>"seek"</strong>, <em>"positionSeconds"</em>: <strong>42.5</strong> <span>}</span></div></div>
            <div class="event-checks">${list(scene.checks, (check, index) => `<p><span>0${index + 1}</span>${escapeHtml(check)}</p>`)}</div>
          </div>
          <div class="truth-strip"><span class="truth-label">IMPLEMENTATION TRUTH</span>${escapeHtml(scene.truth)}</div>
        </div>`;

      case 'persistence':
        return `<div class="scene-layout persistence-layout">${common}
          <div class="persistence-body">
            <div class="database-visual"><div class="db-ring ring-one"></div><div class="db-ring ring-two"></div><div class="db-cylinder"><span class="db-top"></span><strong>${escapeHtml(scene.database)}</strong><small>DURABLE EDGE</small></div><div class="db-wire wire-one"></div><div class="db-wire wire-two"></div><span class="wire-label label-one">JDBC</span><span class="wire-label label-two">TRANSACTIONAL</span></div>
            <div class="migration-list">${list(scene.migrations, (migration) => `<div class="migration"><span class="migration-version">${escapeHtml(migration.version)}</span><div><strong>${escapeHtml(migration.title)}</strong><p>${escapeHtml(migration.detail)}</p></div><span class="migration-state">APPLIED</span></div>`)}</div>
            <div class="property-list">${list(scene.properties, (property) => `<p><span>+</span>${escapeHtml(property)}</p>`)}</div>
          </div>
          <div class="data-wire-labels">${list(scene.wire, (item, index) => `<span class="wire-node wire-node-${index + 1}">${escapeHtml(item)}</span>`)}</div>
        </div>`;

      case 'ai':
        return `<div class="scene-layout ai-layout">${common}
          <div class="ai-body">
            <div class="question-card"><span class="question-label">QUESTION / MEMBER CONTEXT</span><p>“${escapeHtml(scene.question)}”</p><div class="answer-rule"></div><span class="answer-label">GROUNDED ANSWER</span><p class="answer">${escapeHtml(scene.answer)}</p><div class="citation-list">${list(scene.citations, (citation) => `<div class="citation"><span class="citation-id">${escapeHtml(citation.id)}</span><span>${escapeHtml(citation.source)}</span><time>${escapeHtml(citation.time)}</time></div>`)}</div></div>
            <div class="retrieval-panel"><div class="retrieval-header"><span>RETRIEVAL WINDOW</span><strong>−120s</strong></div>${list(scene.retrieval, (item, index) => `<p><span>${String(index + 1).padStart(2, '0')}</span>${escapeHtml(item)}</p>`)}<div class="ai-disclosure"><span>!</span>${escapeHtml(scene.disclosure)}</div></div>
          </div>
        </div>`;

      case 'proof':
        return `<div class="scene-layout proof-layout">${common}
          <div class="proof-body">
            <div class="test-card"><div class="test-number">${escapeHtml(scene.testSummary.number)}</div><div class="test-copy"><strong>${escapeHtml(scene.testSummary.label)}</strong><span>${escapeHtml(scene.testSummary.result)}</span><small>${escapeHtml(scene.testSummary.date)}</small></div><span class="test-check">✓</span></div>
            <div class="proof-columns"><div class="proof-column"><h2><span class="column-dot verified"></span>VERIFIED IN THE SLICE</h2>${list(scene.coverage, (item) => `<p><span>✓</span>${escapeHtml(item)}</p>`)}</div><div class="proof-column gaps"><h2><span class="column-dot open"></span>REMAINING GAPS</h2>${list(scene.gaps, (item) => `<p><span>↗</span>${escapeHtml(item)}</p>`)}</div></div>
          </div>
        </div>`;

      case 'close':
        return `<div class="scene-layout close-layout">${common}
          <div class="close-body"><div class="summary-orbit" aria-hidden="true"><span></span><span></span><span></span><strong>END<br>OF<br>SLICE</strong></div><div class="summary-list">${list(scene.summary, (item, index) => `<div class="summary-item"><span>0${index + 1} / ${escapeHtml(item.label)}</span><strong>${escapeHtml(item.value)}</strong></div>`)}</div></div>
          <div class="close-footer"><span class="close-disclosure">${escapeHtml(scene.footer)}</span><span class="close-cta">${escapeHtml(scene.cta)} <b>→</b></span></div>
        </div>`;

      default:
        return `<div class="scene-layout"><div class="scene-heading"><h1>${escapeHtml(scene.title)}</h1><p class="scene-dek">${escapeHtml(scene.dek)}</p></div></div>`;
    }
  }

  function buildDots() {
    sceneDots.innerHTML = state.scenes.map((scene, index) => `<button class="scene-dot" type="button" role="tab" aria-label="Go to ${escapeHtml(scene.label)}" aria-selected="${index === state.index}" data-index="${index}"><span></span><b>${String(index + 1).padStart(2, '0')}</b></button>`).join('');
    sceneDots.querySelectorAll('.scene-dot').forEach((dot) => dot.addEventListener('click', () => goTo(Number(dot.dataset.index))));
  }

  function render() {
    const scene = state.scenes[state.index];
    if (!scene) return;
    stage.className = `stage stage-${scene.type}`;
    stage.innerHTML = renderScene(scene);
    sceneCounter.textContent = `${String(state.index + 1).padStart(2, '0')} / ${String(state.scenes.length).padStart(2, '0')}`;
    sceneLabel.textContent = scene.label;
    progressFill.style.width = `${((state.index + 1) / state.scenes.length) * 100}%`;
    progressTrack.setAttribute('aria-valuemax', String(state.scenes.length));
    progressTrack.setAttribute('aria-valuenow', String(state.index + 1));
    progressTrack.setAttribute('aria-valuetext', `Scene ${state.index + 1} of ${state.scenes.length}: ${scene.label}`);
    progressTime.textContent = state.playing ? `AUTO / ${(AUTO_ADVANCE_MS / 1000).toFixed(1)}s` : 'PAUSED / MANUAL';
    sceneDots.querySelectorAll('.scene-dot').forEach((dot, index) => {
      dot.setAttribute('aria-selected', String(index === state.index));
      dot.classList.toggle('active', index === state.index);
    });
    srStatus.textContent = `${scene.label}. ${scene.title}`;
  }

  function clearTimer() {
    if (state.timer) window.clearTimeout(state.timer);
    state.timer = null;
  }

  function schedule() {
    clearTimer();
    if (!state.playing || state.scenes.length < 2) return;
    state.startedAt = performance.now();
    state.timer = window.setTimeout(() => {
      goTo((state.index + 1) % state.scenes.length);
    }, AUTO_ADVANCE_MS);
  }

  function goTo(index) {
    if (!state.scenes.length) return;
    state.index = (index + state.scenes.length) % state.scenes.length;
    state.elapsedBeforePause = 0;
    render();
    schedule();
  }

  function togglePlaying() {
    state.playing = !state.playing;
    playIcon.textContent = state.playing ? 'Ⅱ' : '▶';
    playButton.setAttribute('aria-label', state.playing ? 'Pause autoplay' : 'Play autoplay');
    playButton.setAttribute('title', `${state.playing ? 'Pause' : 'Play'} autoplay (Space)`);
    render();
    schedule();
  }

  function handleKeydown(event) {
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName)) return;
    if (event.key === ' ' || event.code === 'Space') {
      event.preventDefault();
      togglePlaying();
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      goTo(state.index + 1);
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      goTo(state.index - 1);
    } else if (event.key === 'Home') {
      event.preventDefault();
      goTo(0);
    } else if (event.key === 'End') {
      event.preventDefault();
      goTo(state.scenes.length - 1);
    }
  }

  async function init() {
    try {
      const response = await fetch('scenes.json', { cache: 'no-store' });
      if (!response.ok) throw new Error(`Could not load scenes.json (${response.status})`);
      const payload = await response.json();
      if (!Array.isArray(payload.scenes) || payload.scenes.length === 0) throw new Error('scenes.json contains no scenes');
      state.scenes = payload.scenes;
      playIcon.textContent = state.playing ? 'Ⅱ' : '▶';
      playButton.setAttribute('aria-label', state.playing ? 'Pause autoplay' : 'Play autoplay');
      playButton.setAttribute('title', `${state.playing ? 'Pause' : 'Play'} autoplay (Space)`);
      buildDots();
      render();
      schedule();
    } catch (error) {
      state.playing = false;
      stage.className = 'stage stage-error';
      stage.innerHTML = `<div class="error-state"><span class="error-icon">!</span><h1>Scene source unavailable.</h1><p>Start the local server from the project root with <code>python3 -m http.server 4173 --directory showcase</code>, then reload.</p><small>${escapeHtml(error.message)}</small></div>`;
      sceneLabel.textContent = 'SOURCE ERROR';
      progressTime.textContent = 'MANUAL / UNAVAILABLE';
      playButton.disabled = true;
      previousButton.disabled = true;
      nextButton.disabled = true;
    }
  }

  previousButton.addEventListener('click', () => goTo(state.index - 1));
  nextButton.addEventListener('click', () => goTo(state.index + 1));
  playButton.addEventListener('click', togglePlaying);
  document.addEventListener('keydown', handleKeydown);
  init();
})();
