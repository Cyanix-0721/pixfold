# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Self-contained HTML/CSS/JS gallery UI for scripts/live_preview.py."""

from __future__ import annotations

GALLERY_HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>PixFold 实时 UI 预览画廊</title>
<style>
  :root {
    color-scheme: light dark;
    --bg: light-dark(#f4f5f8, #141417);
    --surface: light-dark(#ffffff, #1e1e23);
    --surface-alt: light-dark(#eceef3, #27272e);
    --border: light-dark(#d6dae3, #35353f);
    --text: light-dark(#17181c, #eceef4);
    --muted: light-dark(#5c616e, #9da3b0);
    --brand: light-dark(#4f46e5, #9fa8ff);
    --brand-bg: light-dark(#eef0ff, #282a46);
    --ok: light-dark(#137333, #6dd58c);
    --ok-bg: light-dark(#e6f4ea, #163322);
    --warn: light-dark(#b06000, #f6ad55);
    --warn-bg: light-dark(#fef3e2, #382714);
    --err: light-dark(#b3261e, #f28b82);
    --err-bg: light-dark(#fce8e6, #3d1c1a);
    --stage-bg: #2b2b2e;
    --stage-pattern: none;
    --radius: 12px;
  }

  * { box-sizing: border-box; }

  body {
    margin: 0;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans SC", sans-serif;
    background: var(--bg);
    color: var(--text);
    line-height: 1.45;
  }

  header.toolbar {
    position: sticky;
    top: 0;
    z-index: 10;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
    padding: 12px 18px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  }

  .row.between {
    justify-content: space-between;
  }

  .title-block {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  h1 {
    font-size: 16px;
    margin: 0;
    font-weight: 650;
  }

  .pill {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    padding: 3px 10px;
    border-radius: 999px;
    background: var(--surface-alt);
    border: 1px solid var(--border);
    color: var(--muted);
  }

  .pill.ok { color: var(--ok); background: var(--ok-bg); border-color: transparent; }
  .pill.busy { color: var(--warn); background: var(--warn-bg); border-color: transparent; }
  .pill.err { color: var(--err); background: var(--err-bg); border-color: transparent; }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: currentColor;
  }

  .seg-group {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 4px;
    background: var(--surface-alt);
    padding: 3px;
    border-radius: 9px;
    border: 1px solid var(--border);
  }

  button, select {
    font: inherit;
    color: inherit;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 7px;
    padding: 5px 10px;
    font-size: 13px;
    cursor: pointer;
  }

  button:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }

  .seg-btn {
    border: none;
    background: transparent;
    padding: 4px 10px;
    border-radius: 6px;
    font-size: 12.5px;
    color: var(--muted);
  }

  .seg-btn[aria-pressed="true"] {
    background: var(--brand-bg);
    color: var(--brand);
    font-weight: 600;
  }

  .primary-btn {
    background: var(--brand);
    color: #ffffff;
    border-color: transparent;
    font-weight: 600;
  }

  .check-label {
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    cursor: pointer;
    user-select: none;
  }

  input[type="checkbox"],
  input[type="range"] {
    accent-color: var(--brand);
  }

  @supports not (accent-color: auto) {
    .check-label input[type="checkbox"].native-check {
      position: absolute;
      clip-path: inset(50%);
      overflow: hidden;
      width: 1px;
      height: 1px;
      margin: -1px;
      padding: 0;
      border: 0;
      white-space: nowrap;
    }
    .check-label input[type="checkbox"].native-check + span::before {
      content: "";
      display: inline-block;
      width: 14px;
      height: 14px;
      margin-right: 6px;
      vertical-align: -2px;
      border: 1.5px solid var(--border);
      border-radius: 3px;
      background: var(--surface);
    }
    .check-label input[type="checkbox"].native-check:checked + span::before {
      background: var(--brand);
      border-color: var(--brand);
    }
    input[type="range"] {
      -webkit-appearance: none;
      appearance: none;
      background: transparent;
    }
    input[type="range"]::-webkit-slider-runnable-track {
      height: 6px;
      border-radius: 3px;
      background: linear-gradient(to right, var(--brand) var(--progress, 50%), var(--border) var(--progress, 50%));
    }
    input[type="range"]::-webkit-slider-thumb {
      -webkit-appearance: none;
      appearance: none;
      width: 14px;
      height: 14px;
      margin-top: -4px;
      border-radius: 50%;
      background: var(--brand);
    }
  }

  details.error-drawer {
    margin: 12px 18px 0;
    border: 1px solid var(--err);
    border-radius: var(--radius);
    background: var(--err-bg);
    color: var(--err);
    padding: 8px 14px;
  }

  details.error-drawer summary {
    cursor: pointer;
    font-weight: 600;
    font-size: 13px;
  }

  details.error-drawer pre {
    margin: 8px 0 4px;
    padding: 10px;
    border-radius: 8px;
    background: light-dark(#ffffffcc, #111115cc);
    color: var(--text);
    max-height: 260px;
    overflow: auto;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-word;
  }

  main {
    padding: 18px;
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
    gap: 16px;
  }

  .grid.zoom-1x {
    grid-template-columns: repeat(auto-fill, minmax(390px, 1fr));
  }

  .grid.zoom-2x {
    grid-template-columns: repeat(auto-fill, minmax(750px, 1fr));
  }

  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  .card.has-diff {
    border-color: var(--brand);
    box-shadow: 0 0 0 1px color-mix(in srgb, var(--brand) 35%, transparent);
  }

  .card-head {
    padding: 10px 12px;
    border-bottom: 1px solid var(--border);
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 8px;
  }

  .card-title {
    font-size: 13.5px;
    font-weight: 650;
  }

  .card-meta {
    font-size: 11.5px;
    color: var(--muted);
    margin-top: 2px;
  }

  .stage {
    padding: 12px;
    background-color: var(--stage-bg);
    background-image: var(--stage-pattern);
    background-size: 16px 16px;
    background-position: 0 0, 8px 8px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 260px;
  }

  .stage.stage-gray { --stage-bg: #2b2b2e; --stage-pattern: none; }
  .stage.stage-light { --stage-bg: #e7e9ef; --stage-pattern: none; }
  .stage.stage-black { --stage-bg: #000000; --stage-pattern: none; }
  .stage.stage-checker {
    --stage-bg: #26262a;
    --stage-pattern:
      linear-gradient(45deg, #333339 25%, transparent 25%, transparent 75%, #333339 75%),
      linear-gradient(45deg, #333339 25%, transparent 25%, transparent 75%, #333339 75%);
  }

  .img-frame {
    max-width: 100%;
    overflow: auto;
    display: flex;
    justify-content: center;
  }

  .stage img,
  .stage canvas {
    display: block;
    border-radius: 6px;
    box-shadow: 0 2px 10px rgba(0, 0, 0, 0.35);
    cursor: zoom-in;
  }

  .scale-fit .stage img,
  .scale-fit .stage canvas {
    max-width: 100%;
    max-height: 540px;
    width: auto;
    height: auto;
  }

  .scale-1x .stage img,
  .scale-1x .stage canvas {
    width: 361px;
    height: auto;
  }

  .scale-2x .stage img,
  .scale-2x .stage canvas {
    width: 722px;
    height: auto;
  }

  .side-pair {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
    width: 100%;
  }

  .side-col {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    min-width: 0;
  }

  .side-col img {
    max-width: 100%;
    max-height: 460px;
    height: auto;
  }

  .side-cap {
    font-size: 11px;
    color: #d6d9e0;
    background: rgba(0, 0, 0, 0.45);
    padding: 1px 8px;
    border-radius: 999px;
  }

  .swipe-wrap {
    position: relative;
    display: inline-block;
    max-width: 100%;
    --pos: 50%;
  }

  .swipe-wrap img.top-layer {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: contain;
    clip-path: inset(0 calc(100% - var(--pos)) 0 0);
  }

  .swipe-divider {
    position: absolute;
    top: 0;
    bottom: 0;
    left: var(--pos);
    width: 2px;
    background: var(--brand);
    pointer-events: none;
  }

  .swipe-range {
    width: 100%;
    max-width: 340px;
  }

  .empty-box {
    text-align: center;
    padding: 48px 20px;
    color: var(--muted);
    background: var(--surface);
    border: 1px dashed var(--border);
    border-radius: var(--radius);
  }

  dialog#inspectorDialog {
    width: min(94vw, 1180px);
    max-width: 94vw;
    max-height: 92vh;
    padding: 0;
    border: 1px solid var(--border);
    border-radius: 14px;
    background: var(--surface);
    color: var(--text);
    box-shadow: 0 20px 50px rgba(0, 0, 0, 0.45);
  }

  dialog:modal {
    overscroll-behavior: contain;
  }

  dialog::backdrop {
    overflow: hidden;
    overscroll-behavior: contain;
    background: rgba(0, 0, 0, 0.62);
    backdrop-filter: blur(3px);
  }

  .modal-head {
    padding: 12px 16px;
    border-bottom: 1px solid var(--border);
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  .modal-body {
    padding: 16px;
    overflow: auto;
    max-height: calc(92vh - 64px);
  }

  .modal-stage {
    padding: 16px;
    border-radius: 10px;
    min-height: 360px;
    overflow: auto;
  }

  .modal-stage.scale-fit img,
  .modal-stage.scale-fit canvas {
    max-width: 100%;
    max-height: 74vh;
    height: auto;
  }

  .modal-stage.scale-1x img,
  .modal-stage.scale-1x canvas {
    width: 361px;
    height: auto;
  }

  .modal-stage.scale-2x img,
  .modal-stage.scale-2x canvas {
    width: 722px;
    height: auto;
  }
</style>
</head>
<body>
<header class="toolbar">
  <div class="row between">
    <div class="title-block">
      <h1>PixFold 实时 UI 预览画廊</h1>
      <span id="ssePill" class="pill"><span class="dot"></span><span id="sseText">连接中</span></span>
      <span id="renderPill" class="pill"><span id="renderText">就绪</span></span>
    </div>
    <div class="row">
      <select id="renderTarget" aria-label="渲染目标">
        <option value="home">首页 (home · 4)</option>
        <option value="settings">设置与工作区 (settings · 5)</option>
        <option value="chips">芯片瀑布流 (chips · 4)</option>
        <option value="workflows">工作流页面 (workflows · 5)</option>
        <option value="plan">计划与报告 (plan · 4)</option>
        <option value="dialogs">弹窗与进度 (dialogs · 4)</option>
        <option value="all">全部页面 (all · 25)</option>
      </select>
      <button id="renderBtn" class="primary-btn" type="button">重新渲染</button>
    </div>
  </div>
  <div class="row between">
    <div id="groupTabs" class="seg-group" role="group" aria-label="页面分组"></div>
    <div class="row">
      <div id="modeTabs" class="seg-group" role="group" aria-label="对比模式"></div>
      <div id="scaleTabs" class="seg-group" role="group" aria-label="缩放比例"></div>
      <select id="stageSelect" aria-label="舞台底色">
        <option value="stage-gray">底色: 中性灰</option>
        <option value="stage-checker">底色: 棋盘格</option>
        <option value="stage-light">底色: 浅色</option>
        <option value="stage-black">底色: 纯黑</option>
      </select>
      <label class="check-label">
        <input id="onlyChangedChk" class="native-check" type="checkbox">
        <span>仅看有改动</span>
      </label>
      <label class="check-label">
        <input id="changedFirstChk" class="native-check" type="checkbox" checked>
        <span>有变化优先</span>
      </label>
    </div>
  </div>
</header>

<details id="errorDrawer" class="error-drawer" hidden open>
  <summary id="errorSummary">渲染失败日志</summary>
  <pre id="errorLog"></pre>
</details>

<main id="mainStage" class="scale-fit">
  <div id="cardGrid" class="grid"></div>
</main>

<dialog id="inspectorDialog" closedby="any" aria-labelledby="inspectorTitle">
  <div class="modal-head">
    <div class="title-block">
      <strong id="inspectorTitle">大图对比</strong>
      <span id="inspectorMeta" class="pill"></span>
    </div>
    <div class="row">
      <div id="modalModeTabs" class="seg-group"></div>
      <div id="modalScaleTabs" class="seg-group"></div>
      <button id="closeInspectorBtn" type="button">关闭</button>
    </div>
  </div>
  <div class="modal-body">
    <div id="modalStage" class="stage modal-stage stage-gray scale-fit"></div>
  </div>
</dialog>

<script>
(() => {
  const GROUPS = [
    ["all", "全部"],
    ["home", "首页"],
    ["settings", "设置"],
    ["chips", "芯片"],
    ["workflows", "工作流"],
    ["plan", "计划/报告"],
    ["dialogs", "弹窗"],
  ];
  const MODES = [
    ["live", "实时单图"],
    ["side", "并排对比"],
    ["swipe", "卷帘滑块"],
    ["diff", "差异热力图"],
  ];
  const SCALES = [
    ["scale-fit", "适应"],
    ["scale-1x", "1×"],
    ["scale-2x", "2×"],
  ];

  const state = {
    group: "all",
    mode: "live",
    scale: "scale-fit",
    stageClass: "stage-gray",
    onlyChanged: false,
    changedFirst: true,
    snapshots: [],
    status: {},
    diffs: new Map(),
    inspectId: null,
  };

  const el = {
    ssePill: document.getElementById("ssePill"),
    sseText: document.getElementById("sseText"),
    renderPill: document.getElementById("renderPill"),
    renderText: document.getElementById("renderText"),
    renderTarget: document.getElementById("renderTarget"),
    renderBtn: document.getElementById("renderBtn"),
    groupTabs: document.getElementById("groupTabs"),
    modeTabs: document.getElementById("modeTabs"),
    scaleTabs: document.getElementById("scaleTabs"),
    stageSelect: document.getElementById("stageSelect"),
    onlyChangedChk: document.getElementById("onlyChangedChk"),
    changedFirstChk: document.getElementById("changedFirstChk"),
    errorDrawer: document.getElementById("errorDrawer"),
    errorSummary: document.getElementById("errorSummary"),
    errorLog: document.getElementById("errorLog"),
    mainStage: document.getElementById("mainStage"),
    cardGrid: document.getElementById("cardGrid"),
    dialog: document.getElementById("inspectorDialog"),
    inspectorTitle: document.getElementById("inspectorTitle"),
    inspectorMeta: document.getElementById("inspectorMeta"),
    modalModeTabs: document.getElementById("modalModeTabs"),
    modalScaleTabs: document.getElementById("modalScaleTabs"),
    closeInspectorBtn: document.getElementById("closeInspectorBtn"),
    modalStage: document.getElementById("modalStage"),
  };

  function makeSegButtons(container, items, activeKey, onPick) {
    container.replaceChildren();
    for (const [key, label] of items) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "seg-btn";
      btn.textContent = label;
      btn.setAttribute("aria-pressed", String(key === activeKey));
      btn.addEventListener("click", () => onPick(key));
      container.appendChild(btn);
    }
  }

  function loadImage(url) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error("image load failed"));
      img.src = url;
    });
  }

  async function computeDiff(snap) {
    const cacheKey = `${snap.baseline_url || ""}|${snap.live_url || ""}`;
    const existing = state.diffs.get(snap.id);
    if (existing && existing.cacheKey === cacheKey) return existing;
    if (!snap.has_live || !snap.live_url || !snap.baseline_url) {
      const empty = { cacheKey, ready: false, pct: 0, changedPx: 0, totalPx: 0, dataUrl: null };
      state.diffs.set(snap.id, empty);
      return empty;
    }
    try {
      const [baseImg, liveImg] = await Promise.all([
        loadImage(snap.baseline_url),
        loadImage(snap.live_url),
      ]);
      const w = Math.max(baseImg.naturalWidth, liveImg.naturalWidth);
      const h = Math.max(baseImg.naturalHeight, liveImg.naturalHeight);
      const cBase = document.createElement("canvas");
      const cLive = document.createElement("canvas");
      const cOut = document.createElement("canvas");
      cBase.width = cLive.width = cOut.width = w;
      cBase.height = cLive.height = cOut.height = h;
      const ctxBase = cBase.getContext("2d");
      const ctxLive = cLive.getContext("2d");
      const ctxOut = cOut.getContext("2d");
      ctxBase.drawImage(baseImg, 0, 0);
      ctxLive.drawImage(liveImg, 0, 0);
      const d1 = ctxBase.getImageData(0, 0, w, h).data;
      const d2 = ctxLive.getImageData(0, 0, w, h).data;
      const out = ctxOut.createImageData(w, h);
      const od = out.data;
      let changedPx = 0;
      const totalPx = w * h;
      for (let i = 0; i < d1.length; i += 4) {
        const diff =
          Math.abs(d1[i] - d2[i]) > 2 ||
          Math.abs(d1[i + 1] - d2[i + 1]) > 2 ||
          Math.abs(d1[i + 2] - d2[i + 2]) > 2 ||
          Math.abs(d1[i + 3] - d2[i + 3]) > 2;
        if (diff) {
          changedPx++;
          od[i] = 255;
          od[i + 1] = 59;
          od[i + 2] = 92;
          od[i + 3] = 255;
        } else {
          const lum = Math.round(d2[i] * 0.25 + d2[i + 1] * 0.25 + d2[i + 2] * 0.25);
          od[i] = lum;
          od[i + 1] = lum;
          od[i + 2] = lum;
          od[i + 3] = 175;
        }
      }
      ctxOut.putImageData(out, 0, 0);
      const pct = totalPx > 0 ? (changedPx * 100) / totalPx : 0;
      const result = {
        cacheKey,
        ready: true,
        pct,
        changedPx,
        totalPx,
        dataUrl: cOut.toDataURL("image/png"),
      };
      state.diffs.set(snap.id, result);
      return result;
    } catch (_) {
      return { cacheKey, ready: false, pct: 0, changedPx: 0, totalPx: 0, dataUrl: null };
    }
  }

  function formatDiffBadge(snap, diffInfo) {
    if (!snap.has_live) return { text: "仅基线", cls: "pill" };
    if (!diffInfo || !diffInfo.ready) return { text: "比对中", cls: "pill" };
    if (diffInfo.changedPx === 0) return { text: "无变化", cls: "pill ok" };
    return { text: `变更 ${diffInfo.pct.toFixed(2)}%`, cls: "pill busy" };
  }

  function buildStageContent(snap, mode, diffInfo, onOpenModal) {
    const frag = document.createDocumentFragment();
    const liveSrc = snap.live_url || snap.baseline_url;
    const baseSrc = snap.baseline_url || snap.live_url;

    const attachInspect = (node) => {
      if (onOpenModal) node.addEventListener("click", () => onOpenModal(snap.id));
      return node;
    };

    if (mode === "side" && snap.baseline_url && snap.live_url) {
      const pair = document.createElement("div");
      pair.className = "side-pair";
      for (const [cap, src] of [["基线 (黄金图)", snap.baseline_url], ["当前实时图", snap.live_url]]) {
        const col = document.createElement("div");
        col.className = "side-col";
        const tag = document.createElement("span");
        tag.className = "side-cap";
        tag.textContent = cap;
        const img = attachInspect(document.createElement("img"));
        img.src = src;
        img.alt = `${snap.title} - ${cap}`;
        col.append(tag, img);
        pair.appendChild(col);
      }
      frag.appendChild(pair);
      return frag;
    }

    if (mode === "swipe" && snap.baseline_url && snap.live_url) {
      const wrap = document.createElement("div");
      wrap.className = "swipe-wrap";
      wrap.style.setProperty("--pos", "50%");

      const baseImg = attachInspect(document.createElement("img"));
      baseImg.src = baseSrc;
      baseImg.alt = `${snap.title} 基线`;

      const topImg = attachInspect(document.createElement("img"));
      topImg.src = liveSrc;
      topImg.alt = `${snap.title} 实时`;
      topImg.className = "top-layer";

      const divider = document.createElement("div");
      divider.className = "swipe-divider";
      wrap.append(baseImg, topImg, divider);

      const slider = document.createElement("input");
      slider.type = "range";
      slider.className = "swipe-range";
      slider.min = "0";
      slider.max = "100";
      slider.value = "50";
      slider.style.setProperty("--progress", "50%");
      slider.setAttribute("aria-label", `${snap.title} 卷帘位置`);
      slider.addEventListener("input", (e) => {
        const val = `${e.target.value}%`;
        wrap.style.setProperty("--pos", val);
        e.target.style.setProperty("--progress", val);
      });
      frag.append(wrap, slider);
      return frag;
    }

    if (mode === "diff" && diffInfo && diffInfo.ready && diffInfo.dataUrl) {
      const frame = document.createElement("div");
      frame.className = "img-frame";
      const img = attachInspect(document.createElement("img"));
      img.src = diffInfo.dataUrl;
      img.alt = `${snap.title} 像素差异图`;
      frame.appendChild(img);
      frag.appendChild(frame);
      return frag;
    }

    const frame = document.createElement("div");
    frame.className = "img-frame";
    if (liveSrc) {
      const img = attachInspect(document.createElement("img"));
      img.src = liveSrc;
      img.alt = snap.title;
      frame.appendChild(img);
    } else {
      const empty = document.createElement("span");
      empty.className = "pill";
      empty.textContent = "暂无图像";
      frame.appendChild(empty);
    }
    frag.appendChild(frame);
    return frag;
  }

  function renderHeader() {
    const st = state.status || {};
    const s = st.state || "idle";
    const isBusy = s === "rendering";
    el.renderBtn.disabled = isBusy;

    if (s === "rendering") {
      el.renderPill.className = "pill busy";
      el.renderText.textContent = `编译渲染中 (${st.target || "..."})`;
    } else if (s === "ok") {
      el.renderPill.className = "pill ok";
      el.renderText.textContent = `刚刷新 · ${st.target || "all"} · ${st.elapsed_sec ?? "?"}s (${st.executed ?? "?"} 用例)`;
    } else if (s === "error") {
      el.renderPill.className = "pill err";
      el.renderText.textContent = `渲染失败 (${st.target || "?"})`;
    } else {
      el.renderPill.className = "pill";
      el.renderText.textContent = "就绪（显示基线，可点击重新渲染）";
    }

    if (s === "error" && st.error_tail) {
      el.errorDrawer.hidden = false;
      el.errorSummary.textContent = `渲染失败: ${st.error || "Gradle 构建/测试错误"}（展开查看末尾日志）`;
      el.errorLog.textContent = st.error_tail;
    } else {
      el.errorDrawer.hidden = true;
      el.errorLog.textContent = "";
    }

    makeSegButtons(el.groupTabs, GROUPS, state.group, (g) => {
      state.group = g;
      renderAll();
    });
    makeSegButtons(el.modeTabs, MODES, state.mode, (m) => {
      state.mode = m;
      renderAll();
    });
    makeSegButtons(el.scaleTabs, SCALES, state.scale, (sc) => {
      state.scale = sc;
      renderAll();
    });
  }

  function renderCards() {
    el.mainStage.className = state.scale;
    el.cardGrid.className = `grid ${state.scale === "scale-1x" ? "zoom-1x" : state.scale === "scale-2x" ? "zoom-2x" : ""}`;

    let list = state.snapshots.filter((snap) => {
      if (state.group === "all") return true;
      return (snap.groups || [snap.group]).includes(state.group);
    });

    if (state.onlyChanged) {
      list = list.filter((snap) => {
        const d = state.diffs.get(snap.id);
        return snap.has_live && d && d.ready && d.changedPx > 0;
      });
    }

    if (state.changedFirst) {
      list = [...list].sort((a, b) => {
        const da = state.diffs.get(a.id);
        const db = state.diffs.get(b.id);
        const sa = a.has_live ? ((da && da.changedPx > 0) ? 2 : 1) : 0;
        const sb = b.has_live ? ((db && db.changedPx > 0) ? 2 : 1) : 0;
        return sb - sa;
      });
    }

    if (list.length === 0) {
      const box = document.createElement("div");
      box.className = "empty-box";
      box.textContent = state.onlyChanged
        ? "当前分组没有检测到像素改动（可取消勾选「仅看有改动」查看全部基线/实时图）"
        : "当前分组暂无快照";
      el.cardGrid.replaceChildren(box);
      return;
    }

    const cards = [];
    for (const snap of list) {
      const diffInfo = state.diffs.get(snap.id);
      const badge = formatDiffBadge(snap, diffInfo);
      const hasChange = Boolean(snap.has_live && diffInfo && diffInfo.changedPx > 0);

      const card = document.createElement("article");
      card.className = `card${hasChange ? " has-diff" : ""}`;

      const head = document.createElement("div");
      head.className = "card-head";

      const left = document.createElement("div");
      const title = document.createElement("div");
      title.className = "card-title";
      title.textContent = snap.title;
      const meta = document.createElement("div");
      meta.className = "card-meta";
      meta.textContent = `${snap.file} · ${snap.method}`;
      left.append(title, meta);

      const pill = document.createElement("span");
      pill.className = badge.cls;
      pill.textContent = badge.text;

      head.append(left, pill);

      const stage = document.createElement("div");
      stage.className = `stage ${state.stageClass}`;
      stage.appendChild(buildStageContent(snap, state.mode, diffInfo, openInspector));

      card.append(head, stage);
      cards.push(card);
    }
    el.cardGrid.replaceChildren(...cards);
  }

  function renderInspector() {
    if (!state.inspectId || !el.dialog.open) return;
    const snap = state.snapshots.find((s) => s.id === state.inspectId);
    if (!snap) return;
    const diffInfo = state.diffs.get(snap.id);
    const badge = formatDiffBadge(snap, diffInfo);

    el.inspectorTitle.textContent = snap.title;
    el.inspectorMeta.className = badge.cls;
    el.inspectorMeta.textContent = `${snap.file} · ${badge.text}`;

    makeSegButtons(el.modalModeTabs, MODES, state.mode, (m) => {
      state.mode = m;
      renderAll();
    });
    makeSegButtons(el.modalScaleTabs, SCALES, state.scale, (sc) => {
      state.scale = sc;
      renderAll();
    });

    el.modalStage.className = `stage modal-stage ${state.stageClass} ${state.scale}`;
    el.modalStage.replaceChildren(buildStageContent(snap, state.mode, diffInfo, null));
  }

  function openInspector(snapId) {
    state.inspectId = snapId;
    if (!el.dialog.open) el.dialog.showModal();
    renderInspector();
  }

  function renderAll() {
    renderHeader();
    renderCards();
    renderInspector();
  }

  async function refreshState() {
    const resp = await fetch("/api/state", { cache: "no-store" });
    if (!resp.ok) return;
    const data = await resp.json();
    state.snapshots = data.snapshots || [];
    state.status = data.status || {};
    renderAll();

    await Promise.all(
      state.snapshots
        .filter((s) => s.has_live)
        .map(async (s) => {
          await computeDiff(s);
        }),
    );
    renderAll();
  }

  el.stageSelect.addEventListener("change", (e) => {
    state.stageClass = e.target.value;
    renderAll();
  });
  el.onlyChangedChk.addEventListener("change", (e) => {
    state.onlyChanged = e.target.checked;
    renderAll();
  });
  el.changedFirstChk.addEventListener("change", (e) => {
    state.changedFirst = e.target.checked;
    renderAll();
  });

  el.renderBtn.addEventListener("click", async () => {
    el.renderBtn.disabled = true;
    try {
      const resp = await fetch("/api/render", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ target: el.renderTarget.value }),
      });
      if (!resp.ok) {
        const msg = await resp.text();
        state.status = { state: "error", target: el.renderTarget.value, error: `HTTP ${resp.status}`, error_tail: msg };
        renderHeader();
      }
    } finally {
      setTimeout(() => { refreshState(); }, 400);
    }
  });

  el.closeInspectorBtn.addEventListener("click", () => el.dialog.close());
  el.dialog.addEventListener("close", () => { state.inspectId = null; });

  // Mandatory fallback for browsers without <dialog closedby="any"> support
  if (!("closedBy" in HTMLDialogElement.prototype)) {
    el.dialog.addEventListener("click", (event) => {
      if (event.target !== el.dialog) return;
      const rect = el.dialog.getBoundingClientRect();
      const inside =
        rect.top <= event.clientY &&
        event.clientY <= rect.top + rect.height &&
        rect.left <= event.clientX &&
        event.clientX <= rect.left + rect.width;
      if (!inside) el.dialog.close();
    });
  }

  function connectSse() {
    const es = new EventSource("/events");
    es.onopen = () => {
      el.ssePill.className = "pill ok";
      el.sseText.textContent = "SSE 已连接";
    };
    es.addEventListener("update", () => {
      refreshState();
    });
    es.onerror = () => {
      el.ssePill.className = "pill busy";
      el.sseText.textContent = "SSE 重连中...";
    };
  }

  refreshState();
  connectSse();
})();
</script>
</body>
</html>
"""
