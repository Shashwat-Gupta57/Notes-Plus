// Audio notes (AudioNoteActivity / AudioNoteEditor): any number of recordings, each with its
// waveform. Recorded as 16-bit mono WAV at 22050 Hz, like the desktop, because that plays on
// every phone and the PC; the waveform (48 samples) is taken while recording.

import { h, icon, iconButton, toast, prompt, confirm } from './ui.js';
import { recordings, recordingsJson, formatDuration, mediaName } from './model.js';

const RATE = 22050;
const cssVar = name => getComputedStyle(document.documentElement).getPropertyValue('--' + name).trim();
const WAVE = 48;

function wavBlob(chunks, rate) {
  const length = chunks.reduce((n, c) => n + c.length, 0);
  const buf = new ArrayBuffer(44 + length * 2);
  const v = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); v.setUint32(4, 36 + length * 2, true); str(8, 'WAVE');
  str(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, 'data'); v.setUint32(40, length * 2, true);
  let o = 44;
  for (const c of chunks) for (let i = 0; i < c.length; i++, o += 2) v.setInt16(o, Math.max(-1, Math.min(1, c[i])) * 0x7fff, true);
  return new Blob([buf], { type: 'audio/wav' });
}

/** Downsamples to `rate` (simple averaging; speech needs no more). */
function resample(input, from, to) {
  if (from === to) return Float32Array.from(input);
  const ratio = from / to, out = new Float32Array(Math.floor(input.length / ratio));
  for (let i = 0; i < out.length; i++) {
    const a = Math.floor(i * ratio), b = Math.min(input.length, Math.floor((i + 1) * ratio));
    let s = 0;
    for (let k = a; k < b; k++) s += input[k];
    out[i] = s / Math.max(1, b - a);
  }
  return out;
}

function drawWave(canvas, wave, fraction, color, played) {
  const ratio = window.devicePixelRatio || 1, w = canvas.clientWidth || 200, hgt = canvas.clientHeight || 28;
  canvas.width = Math.round(w * ratio);
  canvas.height = Math.round(hgt * ratio);
  const g = canvas.getContext('2d');
  g.setTransform(ratio, 0, 0, ratio, 0, 0);
  const n = wave.length || 1, bar = Math.max(2, w / n - 2);
  wave.forEach((a, i) => {
    const x = i * (w / n), bh = Math.max(2, a * hgt);
    g.fillStyle = i / n < fraction ? played : color;
    g.beginPath();
    g.roundRect ? g.roundRect(x, (hgt - bh) / 2, bar, bh, bar / 2) : g.rect(x, (hgt - bh) / 2, bar, bh);
    g.fill();
  });
}

export function audioEditor(ctx) {
  const list = recordings(ctx.note.data);
  const title = h('input.map-title', { type: 'text', placeholder: 'Audio note', readOnly: ctx.readOnly, maxlength: 500 });
  title.value = ctx.note.title || '';
  title.addEventListener('input', () => ctx.changed());
  const rows = h('div.rec-list');
  const empty = h('div.rec-empty', 'Tap the microphone to record.');
  const timerLabel = h('div.rec-timer', '0:00');
  const live = h('canvas.rec-live');
  let recorder = null;
  let playing = null;

  function render() {
    rows.replaceChildren(...list.map(row));
    empty.hidden = list.length > 0 || !!recorder;
  }

  function row(r) {
    const wave = h('canvas.rec-wave');
    const playBtn = h('button.rec-play', { type: 'button', title: 'Play' }, icon('play', 22, 'var(--on-brand)'));
    const label = h('div.rec-label', r.label || 'Recording');
    const meta = h('div.rec-meta', formatDuration(r.duration) + '  ·  ' + new Date(r.createdAt || Date.now()).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }));
    const del = ctx.readOnly ? null : iconButton('delete', 'Delete recording', () => confirm('Delete this recording?', null, 'Delete', () => {
      if (playing && playing.r === r) stop();
      list.splice(list.indexOf(r), 1);
      render();
      ctx.changed();
    }, true));
    const el = h('div.rec-row', playBtn, h('div.rec-texts', label, wave, meta), del);
    if (!ctx.readOnly) label.addEventListener('click', () => prompt({ title: 'Name this recording', hint: 'Name', value: r.label, allowEmpty: true, onOk: v => { r.label = v.trim(); render(); ctx.changed(); } }));
    const paint = f => drawWave(wave, r.wave, f, cssVar('secondary'), cssVar('brand'));
    requestAnimationFrame(() => paint(0));
    playBtn.addEventListener('click', () => toggle(r, playBtn, paint));
    return el;
  }

  async function toggle(r, btn, paint) {
    if (playing && playing.r === r) {
      if (playing.audio.paused) playing.audio.play(); else playing.audio.pause();
      return;
    }
    stop();
    const url = await ctx.fileUrl(r.file);
    if (!url) { toast('This recording is on another device; connect Google Drive to get it'); return; }
    const audio = new Audio(url);
    const tick = () => { if (playing && playing.audio === audio) { paint(audio.duration ? audio.currentTime / audio.duration : 0); requestAnimationFrame(tick); } };
    audio.addEventListener('play', () => { btn.replaceChildren(icon('pause', 22, 'var(--on-brand)')); tick(); });
    audio.addEventListener('pause', () => btn.replaceChildren(icon('play', 22, 'var(--on-brand)')));
    audio.addEventListener('ended', () => { paint(0); stop(); });
    playing = { r, audio, btn, paint };
    audio.play().catch(() => toast('Couldn\'t play that recording'));
  }

  function stop() {
    if (!playing) return;
    playing.audio.pause();
    playing.btn.replaceChildren(icon('play', 22, 'var(--on-brand)'));
    playing.paint(0);
    playing = null;
  }

  async function start() {
    stop();
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
    } catch {
      toast('Notes+ needs the microphone. Allow it in your browser and try again.');
      return;
    }
    const ac = new (window.AudioContext || window.webkitAudioContext)();
    const src = ac.createMediaStreamSource(stream);
    const proc = ac.createScriptProcessor(4096, 1, 1);
    const chunks = [], peaks = [];
    let peak = 0;
    proc.onaudioprocess = e => {
      const data = e.inputBuffer.getChannelData(0);
      chunks.push(resample(data, ac.sampleRate, RATE));
      for (let i = 0; i < data.length; i++) peak = Math.max(peak, Math.abs(data[i]));
    };
    src.connect(proc);
    proc.connect(ac.destination);
    const started = performance.now();
    const sampler = setInterval(() => {
      peaks.push(Math.min(1, peak * 1.6));
      peak = 0;
      timerLabel.textContent = formatDuration(performance.now() - started);
      drawWave(live, peaks.slice(-60), 1, cssVar('secondary'), cssVar('destructive'));
    }, 60);
    recorder = { stream, ac, proc, src, chunks, peaks, started, sampler };
    recBtn.classList.add('recording');
    recBtn.replaceChildren(icon('stop', 28, '#fff'));
    recPanel.hidden = false;
    render();
  }

  async function finish() {
    const r = recorder;
    recorder = null;
    clearInterval(r.sampler);
    r.proc.disconnect();
    r.src.disconnect();
    r.stream.getTracks().forEach(t => t.stop());
    await r.ac.close();
    recBtn.classList.remove('recording');
    recBtn.replaceChildren(icon('mic', 28, 'var(--on-brand)'));
    recPanel.hidden = true;
    const duration = Math.round(performance.now() - r.started);
    if (duration < 500) { render(); return; }
    const name = mediaName('rec', 'wav').replace(/^rec_(\d+)_[0-9a-f]+/, 'rec_$1');
    await ctx.putFile(name, wavBlob(r.chunks, RATE));
    const wave = [];
    for (let i = 0; i < WAVE; i++) {
      const a = Math.floor(i * r.peaks.length / WAVE), b = Math.max(a + 1, Math.floor((i + 1) * r.peaks.length / WAVE));
      wave.push(Math.max(0.05, ...r.peaks.slice(a, b)));
    }
    list.unshift({ file: name, label: 'Recording ' + (list.length + 1), duration, createdAt: Date.now(), wave });
    render();
    ctx.changed();
  }

  const recBtn = h('button.rec-btn', { type: 'button', title: 'Record', onClick: () => (recorder ? finish() : start()) }, icon('mic', 28, 'var(--on-brand)'));
  const recPanel = h('div.rec-panel', h('span.rec-dot'), timerLabel, live);
  recPanel.hidden = true;
  render();

  return {
    node: h('div.page', h('div.map-head', title), recPanel, empty, rows, ctx.readOnly ? null : h('div.rec-dock', recBtn)),
    collect(note) {
      note.title = title.value.trim();
      note.data = recordingsJson(list);
      note.content = list.map(r => r.label).filter(Boolean).join('\n');
      return list.length > 0 || !!note.title;
    },
    destroy() {
      stop();
      if (recorder) {
        // Leaving mid-recording: the microphone stops; the unfinished take isn't kept.
        clearInterval(recorder.sampler);
        recorder.stream.getTracks().forEach(t => t.stop());
        recorder.ac.close();
        recorder = null;
      }
    },
  };
}
