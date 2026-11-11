// 틈틈 디자인 보드 공통: 헤더 내비, 줌, 아이콘
const BOARDS = [
  ['index.html', '개요'],
  ['components.html', '컴포넌트'],
  ['key-8.html', '우선 시안 8'],
  ['home.html', '홈'],
  ['session.html', '세션 공통'],
  ['f1.html', 'F1 가면 쓰기'],
  ['f2.html', 'F2 리차지'],
  ['f3.html', 'F3 리허설'],
  ['f4.html', 'F4 가면 벗기'],
  ['q1-system.html', 'Q1 · 예외'],
  ['onboarding.html', '온보딩'],
  ['records-settings.html', '기록 · 설정'],
  ['display.html', '17" 화면 맥락'],
];
const ZOOMS = [.25, .33, .42, .5, .75, 1];

const here = location.pathname.split('/').pop() || 'index.html';
const head = document.createElement('header');
head.className = 'board-head';
head.innerHTML = `<h1>틈틈 UI</h1><nav>${BOARDS.map(([f, t]) =>
  `<a href="${f}"${f === here ? ' aria-current="page"' : ''}>${t}</a>`).join('')}</nav>
  <div class="board-zoom" role="group" aria-label="보드 배율">${ZOOMS.map(z =>
  `<button type="button" data-z="${z}">${Math.round(z * 100)}%</button>`).join('')}</div>`;
document.body.prepend(head);

const setZoom = z => {
  document.body.style.setProperty('--z', z);
  head.querySelectorAll('[data-z]').forEach(b => b.setAttribute('aria-pressed', String(+b.dataset.z === z)));
  try { localStorage.setItem('teum.zoom', z); } catch {}
};
head.addEventListener('click', e => { const b = e.target.closest('[data-z]'); if (b) setZoom(+b.dataset.z); });
let saved = +document.body.dataset.z || .42;
try { saved = +localStorage.getItem('teum.zoom') || saved; } catch {}
setZoom(+new URLSearchParams(location.search).get('z') || saved);

// ?f=F1-03 → 그 화면만 보기 (시안 이미지 뽑을 때)
const only = new URLSearchParams(location.search).get('f');
if (only) {
  document.querySelectorAll('.shot').forEach(s => { if (!s.querySelector(`[data-screen="${only}"]`)) s.remove(); });
  document.querySelectorAll('.flow').forEach(f => { if (!f.querySelector('.shot')) f.remove(); });
}

if (window.lucide) lucide.createIcons();
