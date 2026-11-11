// 그래픽 라이브러리: 사물을 연상시키지 않는 추상·기하 형태만 쓴다.
// 모양마다 2~3색 메시 그라디언트 + 그레인, 필요하면 물 질감(변위)·하프톤 점.
// 팔레트: [주색, 대비되는 두 번째 색, 밝은 세 번째 색, 하이라이트]
const PAL = {
  home:    ['#FF7A45', '#B9A4FF', '#FFC48A', '#FFF3E6'],
  sunrise: ['#FF5E3A', '#B9A4FF', '#FFB067', '#FFF1DE'],
  dusk:    ['#D9479B', '#4B3CB8', '#FFB38F', '#FFE3D3'],
  night:   ['#3B2C8F', '#1B1440', '#8B5CC9', '#D9C2FF'],
  coral:   ['#FF5F8F', '#6B2E8F', '#FFC15E', '#FFF1DC'],
  ember:   ['#FF4E2E', '#8B6BFF', '#FFC25C', '#FFE7CF'],
  rose:    ['#FF5C8A', '#6FB8FF', '#FFD0DE', '#FFFFFF'],
  calm:    ['#5A6CFF', '#8FE3CF', '#E3E7FF', '#FFFFFF'],
  firm:    ['#FF4220', '#7A2C6E', '#FFC03D', '#FFE9C7'],
  warm:    ['#FF6F86', '#B79BFF', '#FFC0A0', '#FFF0EA'],
  focus:   ['#8FB0D4', '#D7F25A', '#EEF3F8', '#FFFFFF'],
  flex:    ['#4FB985', '#7DBDFF', '#F0EC7A', '#F2FAF0'],
  wake:    ['#FF8A3D', '#FFD23F', '#FFE9A8', '#FFFFFF'],
  still:   ['#6B6158', '#A89A8C', '#E2D8CC', '#F5EFE8'],
  mist:    ['#8FA8E6', '#C4B2F2', '#DDEBFA', '#FFFFFF'],
};
// 시간대별 시그니처 형태: 실루엣부터 달라야 한다 (아치 · 누운 캡슐 · 둥근 삼각형 · 겹친 두 원)
const SIG = { am: 'arch', noon: 'pill', meet: 'tri', pm: 'venn', q1: 'amoeba' };
const SIGPAL = { am: 'sunrise', noon: 'dusk', meet: 'coral', pm: 'ember', q1: 'rose' };
const MOODSHAPE = { calm: 'orb', firm: 'tile', warm: 'amoeba', focus: 'target', flex: 'cluster' };

let _uid = 0;
const _RM = matchMedia('(prefers-reduced-motion: reduce)').matches;
const AMOEBA = 'M150 70C210 40 300 70 330 130C356 184 334 222 300 250C262 282 268 336 214 350C150 366 92 330 76 270C60 214 96 190 104 150C110 116 112 90 150 70Z';

function SHAPE(kind, pal = 'home', size = 360, o = {}) {
  const u = 's' + (++_uid), c = PAL[pal] || PAL.home, live = size >= 200 && !_RM;
  const blur = o.blur ?? { orb: 10, amoeba: 9, halo: 12, cluster: 16, tile: 6, target: 10, arch: 2, pill: 8, tri: 9, venn: 0, bands: 12, drop: 12, ring: 9, gust: 10 }[kind] ?? 8;
  const fx = `<filter id="${u}f" filterUnits="userSpaceOnUse" x="-120" y="-120" width="640" height="640" color-interpolation-filters="sRGB">
    ${o.liquid ? `<feTurbulence type="fractalNoise" baseFrequency=".012" numOctaves="2" seed="${_uid % 9}" result="w">${live ? '<animate attributeName="baseFrequency" dur="11s" values=".012;.019;.012" repeatCount="indefinite"/>' : ''}</feTurbulence><feDisplacementMap in="SourceGraphic" in2="w" scale="40" xChannelSelector="R" yChannelSelector="B" result="s0"/>` : ''}
    <feGaussianBlur in="${o.liquid ? 's0' : 'SourceGraphic'}" stdDeviation="${blur}" result="s"/>
    <feTurbulence type="fractalNoise" baseFrequency=".95" numOctaves="2" seed="5" result="n"/>
    <feColorMatrix in="n" values="0 0 0 0 .12  0 0 0 0 .08  0 0 0 0 .06  .55 0 0 0 -.28" result="na"/>
    <feComposite in="na" in2="s" operator="in" result="g"/>
    <feMerge><feMergeNode in="s"/><feMergeNode in="g"/></feMerge></filter>`;
  const grads = `<linearGradient id="${u}l" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c[0]}"/><stop offset="1" stop-color="${c[1]}"/></linearGradient>
    <radialGradient id="${u}a" cx=".78" cy=".8" r=".62"><stop offset="0" stop-color="${c[2]}"/><stop offset="1" stop-color="${c[2]}" stop-opacity="0"/></radialGradient>
    <radialGradient id="${u}b" cx=".32" cy=".28" r=".42"><stop offset="0" stop-color="${c[3]}" stop-opacity=".95"/><stop offset="1" stop-color="${c[3]}" stop-opacity="0"/></radialGradient>`;
  const mesh = el => ['l', 'a', 'b'].map(g => el.replace('/>', ` fill="url(#${u}${g})"/>`)).join('');
  const halftone = `<pattern id="${u}p" width="7" height="7" patternUnits="userSpaceOnUse"><circle cx="3.5" cy="3.5" r="2.5" fill="#fff"/></pattern><mask id="${u}h"><rect width="400" height="400" fill="url(#${u}p)"/></mask><filter id="${u}g" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="14"/></filter>`;
  let body = '', extra = '', defs = '', crisp = false;
  switch (kind) {
    case 'orb': // 레퍼런스 'modern gradient blob': 둥근 빛 덩어리, 가장자리에 다른 색
      body = `<circle cx="200" cy="200" r="158" fill="${c[1]}" opacity=".9"/>` + mesh('<circle class="pa" cx="196" cy="194" r="128"/>'); break;
    case 'halo': // 30 gradient blurs: 노란 빛 무리 안에 한쪽으로 치우친 깊은 코어
      defs = `<radialGradient id="${u}o" cx=".44" cy=".42" r=".6"><stop offset="0" stop-color="${c[0]}"/><stop offset=".6" stop-color="${c[2]}"/><stop offset="1" stop-color="${c[3]}" stop-opacity="0"/></radialGradient>
        <radialGradient id="${u}i" cx=".6" cy=".62" r=".62"><stop offset="0" stop-color="${c[1]}"/><stop offset=".5" stop-color="${c[1]}" stop-opacity=".8"/><stop offset="1" stop-color="${c[0]}" stop-opacity="0"/></radialGradient>`;
      body = `<circle cx="200" cy="200" r="178" fill="url(#${u}o)"/><circle class="pb" cx="226" cy="224" r="116" fill="url(#${u}i)"/>`; break;
    case 'amoeba': body = mesh(`<path d="${AMOEBA}"/>`) + `<ellipse class="pa" cx="186" cy="190" rx="46" ry="40" fill="${c[3]}" opacity=".55"/>`; break;
    case 'cluster': // 겹친 빛 덩어리들
      body = `<ellipse class="pa" cx="170" cy="150" rx="120" ry="92" fill="${c[1]}"/><ellipse cx="236" cy="236" rx="118" ry="96" fill="${c[0]}"/><ellipse class="pb" cx="160" cy="262" rx="70" ry="56" fill="${c[2]}"/><circle class="pa" cx="210" cy="190" r="46" fill="${c[3]}" opacity=".9"/>`; break;
    case 'tile': body = `<g transform="rotate(14 200 200)">${mesh('<rect x="76" y="76" width="248" height="248" rx="72"/>')}</g>`; break;
    case 'target':
      body = `<circle cx="200" cy="200" r="176" fill="url(#${u}l)" opacity=".85"/><circle cx="200" cy="200" r="176" fill="url(#${u}a)"/>`;
      extra = `<circle cx="200" cy="200" r="104" fill="${c[2]}" fill-opacity=".35" stroke="#fff" stroke-width="3"/><circle cx="200" cy="200" r="58" fill="${c[3]}" fill-opacity=".7"/><circle cx="200" cy="200" r="22" fill="var(--lime)"/>`; break;
    case 'arch': { // 아침 준비: 위가 둥근 아치, 꼭대기에 떠오르는 빛, 뒤로 하프톤 그림자
      crisp = true; const A = 'M96 372V204a104 104 0 0 1 208 0v168z';
      defs = halftone + `<linearGradient id="${u}r" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c[2]}"/><stop offset=".55" stop-color="${c[0]}"/><stop offset="1" stop-color="${c[1]}"/></linearGradient>
        <radialGradient id="${u}s"><stop offset="0" stop-color="${c[3]}"/><stop offset="1" stop-color="${c[3]}" stop-opacity="0"/></radialGradient>`;
      body = `<g mask="url(#${u}h)"><path d="${A}" transform="translate(40 -14)" fill="${c[1]}"/></g>
        <g filter="url(#${u}f)"><path d="${A}" fill="url(#${u}r)"/><circle class="pa" cx="196" cy="176" r="92" fill="url(#${u}s)"/></g>`; break; }
    case 'pill': // 점심 낮잠: 옆으로 누운 캡슐 두 개가 포개진 모양
      defs = `<linearGradient id="${u}r" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${c[2]}"/><stop offset=".5" stop-color="${c[0]}"/><stop offset="1" stop-color="${c[1]}"/></linearGradient>`;
      body = `<g transform="rotate(-9 200 210)"><rect class="pb" x="96" y="98" width="276" height="124" rx="62" fill="${c[1]}" opacity=".9"/><rect x="28" y="176" width="316" height="140" rx="70" fill="url(#${u}r)"/>
        <ellipse class="pa" cx="124" cy="236" rx="66" ry="30" fill="${c[3]}" opacity=".55"/></g>`; break;
    case 'tri': // 마음 준비: 위로 솟은 둥근 삼각형, 아래쪽에 깊은 코어
      defs = `<linearGradient id="${u}o" x1=".35" y1="0" x2=".65" y2="1"><stop offset="0" stop-color="${c[2]}"/><stop offset=".6" stop-color="${c[0]}"/></linearGradient>
        <radialGradient id="${u}i"><stop offset="0" stop-color="${c[1]}"/><stop offset=".5" stop-color="${c[1]}" stop-opacity=".75"/><stop offset="1" stop-color="${c[1]}" stop-opacity="0"/></radialGradient>`;
      body = `<path d="M200 92L326 312H74Z" fill="url(#${u}o)" stroke="url(#${u}o)" stroke-width="84" stroke-linejoin="round"/>
        <ellipse class="pb" cx="240" cy="276" rx="118" ry="74" fill="url(#${u}i)"/>`; break;
    case 'venn': // 옆으로 겹친 두 원, 하프톤
      crisp = true; defs = halftone + `<radialGradient id="${u}q" cx=".7" cy=".75" r=".7"><stop offset="0" stop-color="${c[2]}"/><stop offset=".6" stop-color="${c[1]}"/><stop offset="1" stop-color="${c[1]}"/></radialGradient>`;
      body = `<g filter="url(#${u}g)" opacity=".55"><circle class="pa" cx="160" cy="170" r="118" fill="url(#${u}l)"/><circle class="pb" cx="244" cy="232" r="118" fill="url(#${u}q)"/></g>
        <g mask="url(#${u}h)"><circle class="pa" cx="160" cy="170" r="118" fill="url(#${u}l)"/><circle class="pa" cx="160" cy="170" r="118" fill="url(#${u}b)"/><circle class="pb" cx="244" cy="232" r="118" fill="url(#${u}q)" style="mix-blend-mode:multiply"/></g>`; break;
    // 쉴 때 듣는 소리: 소리를 그리지 않고 결만 떠올리게 한다
    case 'bands': // 파도: 겹겹이 밀려오는 가로 빛 띠
      body = `<ellipse class="pa" cx="200" cy="140" rx="168" ry="44" fill="${c[1]}" opacity=".85"/><ellipse cx="186" cy="208" rx="178" ry="52" fill="url(#${u}l)"/><ellipse class="pb" cx="214" cy="276" rx="150" ry="42" fill="${c[2]}" opacity=".9"/><ellipse cx="150" cy="202" rx="70" ry="20" fill="${c[3]}" opacity=".5"/>`; break;
    case 'drop': // 모닥불: 위로 가늘어지는 빛, 아래에 뜨거운 코어
      defs = `<radialGradient id="${u}r" cx=".5" cy=".74" r=".62"><stop offset="0" stop-color="${c[3]}"/><stop offset=".3" stop-color="${c[2]}"/><stop offset=".8" stop-color="${c[0]}"/><stop offset="1" stop-color="${c[0]}" stop-opacity=".7"/></radialGradient>`;
      body = `<circle cx="200" cy="246" r="136" fill="${c[2]}" opacity=".35"/><path d="M200 78C240 136 300 180 300 254A100 100 0 0 1 100 254C100 180 160 136 200 78Z" fill="url(#${u}r)"/>`; break;
    case 'ring': // 싱잉볼: 울림이 퍼지는 고리
      body = `<circle cx="200" cy="200" r="122" fill="none" stroke="url(#${u}l)" stroke-width="78"/><circle cx="200" cy="200" r="122" fill="none" stroke="url(#${u}a)" stroke-width="78"/><circle class="pa" cx="166" cy="158" r="40" fill="${c[3]}" opacity=".5"/>`; break;
    case 'gust': // 바람: 휘어 지나가는 두 줄기
      body = `<path d="M60 236C112 122 272 98 342 176" fill="none" stroke="url(#${u}l)" stroke-width="60" stroke-linecap="round"/><path class="pb" d="M112 300C170 250 258 248 312 284" fill="none" stroke="${c[1]}" stroke-opacity=".8" stroke-width="44" stroke-linecap="round"/>`; break;
    default: body = mesh('<circle cx="200" cy="200" r="150"/>');
  }
  return `<svg class="shape ${live ? 'live' : ''} ${o.cls || ''}" width="${size}" height="${size}" viewBox="0 0 400 400" aria-hidden="true">
    <defs>${grads}${fx}${defs}</defs>${crisp ? body : `<g filter="url(#${u}f)">${body}</g>`}${extra}</svg>`;
}
// 장소 그림: 대놓고 그리지 않고 빛과 형태로만 떠올리게 한다
// 집 = 저녁 안개 속 박공 실루엣과 켜진 문, 회사 = 안쪽으로 겹쳐 들어가는 사각 빛과 작은 창
function PLACEART(kind) {
  const u = 'p' + (++_uid), F = (n, d) => `<filter id="${u}${n}" filterUnits="userSpaceOnUse" x="-100" y="-100" width="600" height="500"><feGaussianBlur stdDeviation="${d}"/></filter>`;
  const svg = (defs, body) => `<svg viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><defs>${defs}</defs>${body}</svg>`;
  if (kind === 'home') return svg(`<linearGradient id="${u}s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5263C8"/><stop offset=".4" stop-color="#8F8DE0"/><stop offset=".62" stop-color="#F0A8BE"/><stop offset=".73" stop-color="#FFC79E"/><stop offset=".73" stop-color="#5149BC"/><stop offset="1" stop-color="#271F72"/></linearGradient>
      <linearGradient id="${u}h" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7179D6"/><stop offset=".7" stop-color="#5A58BE"/><stop offset="1" stop-color="#4B47B0"/></linearGradient>
      <linearGradient id="${u}d" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFDDA6"/><stop offset="1" stop-color="#FF9F8A"/></linearGradient>${F(1, 2)}${F(2, 10)}${F(3, 16)}`,
    `<rect width="400" height="300" fill="url(#${u}s)"/><ellipse cx="200" cy="216" rx="260" ry="36" fill="#FFB89A" opacity=".5" filter="url(#${u}3)"/>
      <path d="M110 224V112L200 52L290 112V224Z" fill="url(#${u}h)" filter="url(#${u}1)"/>
      <rect x="183" y="144" width="34" height="80" fill="url(#${u}d)" filter="url(#${u}2)" opacity=".9"/><rect x="183" y="144" width="34" height="80" fill="url(#${u}d)"/>
      <ellipse cx="262" cy="214" rx="190" ry="30" fill="#CDBFFF" opacity=".7" filter="url(#${u}3)"/><ellipse cx="120" cy="228" rx="170" ry="20" fill="#E9C8E4" opacity=".45" filter="url(#${u}3)"/>`);
  return svg(`<radialGradient id="${u}s" cx="1" cy="0" r="1.15"><stop offset="0" stop-color="#171A44"/><stop offset=".5" stop-color="#4253B8"/><stop offset="1" stop-color="#8A9EEE"/></radialGradient>
      <linearGradient id="${u}c" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFA03D"/><stop offset="1" stop-color="#FF4E2E"/></linearGradient>
      <linearGradient id="${u}w" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#C6A6FF"/><stop offset="1" stop-color="#FFB592"/></linearGradient>${F(1, 24)}${F(2, 18)}${F(3, 13)}${F(4, 8)}`,
    `<rect width="400" height="300" fill="url(#${u}s)"/><rect x="34" y="34" width="332" height="320" fill="#8FAEFF" filter="url(#${u}1)"/>
      <rect x="86" y="102" width="228" height="250" fill="#D6B4F4" filter="url(#${u}2)"/><rect x="126" y="152" width="148" height="200" fill="#FF8CAE" filter="url(#${u}3)"/>
      <rect x="150" y="188" width="100" height="160" fill="url(#${u}c)" filter="url(#${u}4)"/><rect x="172" y="236" width="56" height="34" fill="url(#${u}w)"/>`);
}
const MINI = (key, size = 96, dim = false) => SHAPE(SIG[key], SIGPAL[key], size, { cls: dim ? 'dim' : '', blur: key === 'meet' ? 8 : 6 });
