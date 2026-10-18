/* ════════════════════════════════════════════════════════════════════
   GLASS BRIDGE  (SYNC ⇄ Native Liquid Glass)
   - Liquid Glass 표면은 Kotlin + Compose Multiplatform + Kyant `backdrop` 이 WebView 위에 직접 그린다.
   - 이 스크립트는 app.js 를 전혀 수정하지 않는 "읽기 전용 미러 + 명령 수신기" 이다.
       DOM → Native : window.GlassBridge.push(JSON)   (위치/텍스트/재생 상태 — CSS px == dp)
       Native → DOM : window.__glassCmd({...})        (탭/입력을 기존 app.js 함수·DOM 이벤트로 전달)
   - app.js 의 재생·검색·플레이리스트 로직, AndroidBridge 통신은 기존과 100% 동일하게 동작한다.
════════════════════════════════════════════════════════════════════ */
(function () {
    'use strict';
    if (!window.GlassBridge) return; // 일반 브라우저: 기존 DOM UI 그대로

    const $ = id => document.getElementById(id);
    const txt = el => (el ? el.textContent.trim() : '');

    /* DOM <input> 들은 로직의 원천으로만 남기고, 실제 타이핑은 네이티브 필드가 담당 (WebView 키보드 중복 방지) */
    ['q-home', 'q-s', 'pl-dialog-input', 'pl-add-modal-inp'].forEach(id => {
        const el = $(id);
        if (el) { el.readOnly = true; el.setAttribute('inputmode', 'none'); el.setAttribute('autocomplete', 'off'); }
    });

    const box = r => ({ x: r.left, y: r.top, w: r.width, h: r.height });

    /** 요소가 화면에 보이고 다른 UI(예: Now Playing)에 가려져 있지 않은지 */
    function exposed(el) {
        const r = el.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) return false;
        const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) return false;
        const t = document.elementFromPoint(cx, cy);
        if (!t) return false;
        if (t === el || el.contains(t)) return true;
        // 다이얼로그/모달의 딤 스크림은 가림으로 취급하지 않는다 (네이티브가 같이 어둡게 처리)
        return !!(t.closest && t.closest('#pl-dialog-overlay,#pl-confirm-overlay,#pl-add-modal-overlay'));
    }

    /** 고정 중앙 정렬 박스: 등장 애니메이션(scale)에 영향받지 않도록 레이아웃 크기 + 중심 좌표 사용 */
    function stableBox(el) {
        const r = el.getBoundingClientRect();
        const w = el.offsetWidth, h = el.offsetHeight;
        const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        return { x: cx - w / 2, y: cy - h / 2, w: w, h: h };
    }

    /** 검색 캡슐: sticky 로 고정되기 전의 "문서 기준" 위치(flowTop)와 고정 위치(pin) — 스크롤과 무관한 상수.
     *
     *  - 측정은 getBoundingClientRect 가 아니라 레이아웃(offsetTop 체인) 기준이다.
     *    뷰 전환 애니메이션(.view 의 vIn: translateY 10px → 0, 0.3s)이 진행 중이어도 transform 의 영향을 받지 않아
     *    "최종 위치"가 첫 프레임부터 나온다. (이전: 애니메이션 도중 값을 250ms 캐시 → 0.5초 뒤에야 제자리로 정렬)
     *  - 시간 기반 캐시를 없애고 이벤트 기반으로 무효화한다(kick / resize / 애니메이션 종료 / 유휴 시 자가 검증).
     *    → 스크롤 중에는 강제 리플로우가 전혀 일어나지 않는다. */
    let geom = null, geomDirty = true, geomTimer = 0;
    function flowMetrics(wrap) {
        const st = wrap.style;
        const prevVal = st.getPropertyValue('position'), prevPri = st.getPropertyPriority('position');
        st.setProperty('position', 'static', 'important');     // sticky 오프셋이 섞이지 않은 "흐름상 위치"
        let x = 0, y = 0, n = wrap;
        while (n && n !== document.body && n !== document.documentElement) {
            x += n.offsetLeft; y += n.offsetTop;
            const op = n.offsetParent;           // offsetTop 은 부모의 padding edge 기준 → 부모 border 폭 보정
            if (op && op !== document.body && op !== document.documentElement) { x += op.clientLeft; y += op.clientTop; }
            n = op;
        }
        const w = wrap.offsetWidth, h = wrap.offsetHeight;
        if (prevVal) st.setProperty('position', prevVal, prevPri); else st.removeProperty('position');
        mo.takeRecords();            // 측정용 style 변경이 관찰 루프를 만들지 않도록 폐기
        return { x: x, y: y, w: w, h: h };
    }
    function searchGeom(wrap) {
        if (geom && geom.wrap === wrap && !geomDirty) return geom;
        const cs = getComputedStyle(wrap);
        const topV = parseFloat(cs.top);
        const pin = (cs.position === 'sticky' && isFinite(topV)) ? topV : -10000;
        const m = flowMetrics(wrap);
        geom = { wrap: wrap, pin: pin, x: m.x, flowTop: m.y, w: m.w, h: m.h };
        geomDirty = false;
        return geom;
    }

    /** Now Playing 이 열려 있는 동안 문서(WebView) 스크롤을 잠근다.
     *  문서 스크롤 구조에서는 NP 위에서 가사를 드래그하면 뒤의 문서가 같이 스크롤되며(네이티브 스크롤바 노출 +
     *  제스처 충돌) 가사 패널의 부드러운 드래그를 방해했다. */
    const npEl = $('np');
    function syncNpLock() {
        if (npEl) document.documentElement.classList.toggle('np-open', npEl.classList.contains('on'));
    }

    function collect() {
        const st = {};
        st.acc = (typeof _accentColor === 'function' ? _accentColor() : '#fa2d5a');

        /* 하단 내비 */
        const nav = $('mob-nav');
        if (nav && exposed(nav)) {
            const btns = Array.from(nav.querySelectorAll('.mn-btn'));
            st.nav = { r: box(nav.getBoundingClientRect()), active: Math.max(0, btns.findIndex(b => b.classList.contains('on'))) };
        }

        /* 검색 캡슐 (현재 활성 뷰) */
        const wrap = document.querySelector('.view.on .mob-srch-wrap');
        if (wrap && exposed(wrap)) {
            const inp = wrap.querySelector('.srch-inp');
            if (inp) {
                const g = searchGeom(wrap);
                st.search = {
                    r: { x: g.x, y: g.flowTop, w: g.w, h: g.h }, pin: g.pin,
                    id: inp.id, value: inp.value,
                    ph: inp.getAttribute('placeholder') || '', focused: document.activeElement === inp
                };
            }
        }

        /* 미니 플레이어 */
        const bar = $('bar');
        if (bar && !bar.classList.contains('bar-hidden') && exposed(bar)) {
            const art = $('b-art');
            st.bar = {
                r: box(bar.getBoundingClientRect()),
                art: art ? (art.currentSrc || art.getAttribute('src') || '') : '',
                title: txt($('b-title')), ch: txt($('b-ch')),
                playing: /M118 116/.test(($('cb-play') || {}).innerHTML || ''),
                fav: !!($('b-fav') && $('b-fav').classList.contains('on')),
                accent: (typeof _accentColor === 'function' ? _accentColor() : '#fa2d5a')
            };
        }

        /* "플레이리스트에 추가" 컨텍스트 메뉴 */
        const ctx = $('pl-ctx-menu');
        if (ctx && ctx.classList.contains('on')) {
            const items = Array.from(document.querySelectorAll('#pl-ctx-list .pl-ctx-item')).map(txt);
            st.ctx = {
                r: { x: ctx.offsetLeft, y: ctx.offsetTop, w: ctx.offsetWidth, h: ctx.offsetHeight },
                title: txt(ctx.querySelector('.pl-ctx-title')), items: items, new: txt(ctx.querySelector('.pl-ctx-new'))
            };
        }

        /* 이름 입력 다이얼로그 */
        if ($('pl-dialog-overlay') && $('pl-dialog-overlay').classList.contains('on')) {
            const d = $('pl-dialog'), inp = $('pl-dialog-input');
            st.dlg = {
                r: stableBox(d), title: txt($('pl-dialog-title')), value: inp.value,
                ph: inp.getAttribute('placeholder') || '', focused: document.activeElement === inp,
                ok: txt(d.querySelector('.pl-dialog-ok')), cancel: txt(d.querySelector('.pl-dialog-cancel'))
            };
        }

        /* 삭제 확인 다이얼로그 */
        if ($('pl-confirm-overlay') && $('pl-confirm-overlay').classList.contains('on')) {
            const d = $('pl-confirm');
            st.cfm = {
                r: stableBox(d), title: txt($('pl-confirm-title')), msg: txt($('pl-confirm-msg')),
                ok: txt(d.querySelector('.pl-dialog-ok')), cancel: txt(d.querySelector('.pl-dialog-cancel'))
            };
        }

        /* 곡 추가 모달 */
        if ($('pl-add-modal-overlay') && $('pl-add-modal-overlay').classList.contains('on')) {
            const m = $('pl-add-modal'), inp = $('pl-add-modal-inp');
            const rows = Array.from(document.querySelectorAll('#pl-add-modal-results .pl-modal-row')).map(r => {
                const img = r.querySelector('img');
                const btn = r.querySelector('.pl-modal-add-btn');
                return {
                    art: img ? (img.currentSrc || img.getAttribute('src') || '') : '',
                    t: txt(r.querySelector('.pl-modal-row-t')), c: txt(r.querySelector('.pl-modal-row-c')),
                    d: txt(r.querySelector('.pl-modal-row-dur')), added: !!(btn && btn.classList.contains('added'))
                };
            });
            const hint = document.querySelector('#pl-add-modal-results .pl-add-modal-hint');
            st.add = {
                r: stableBox(m), title: txt(m.querySelector('.pl-add-modal-title')), value: inp.value,
                ph: inp.getAttribute('placeholder') || '', focused: document.activeElement === inp,
                rows: rows, hint: rows.length ? null : txt(hint)
            };
        }
        return st;
    }

    /* ── 전송 스케줄링: 변경이 있을 때만, 프레임당 최대 1회 ── */
    let raf = 0, burstUntil = 0, last = '';
    function flush() {
        raf = 0;
        let json;
        try { json = JSON.stringify(collect()); } catch (e) { json = last; }
        if (json !== last) { last = json; window.GlassBridge.push(json); }
        if (performance.now() < burstUntil) raf = requestAnimationFrame(flush);
    }
    function schedule() { if (!raf) raf = requestAnimationFrame(flush); }
    function kick(ms) {
        burstUntil = Math.max(burstUntil, performance.now() + (ms || 700));
        geomDirty = true;                                   // 다음 flush 에서 한 번만 재측정
        clearTimeout(geomTimer);                            // 애니메이션/레이아웃이 끝난 뒤 한 번 더 확정 측정
        geomTimer = setTimeout(() => { geomDirty = true; schedule(); }, (ms || 700) + 60);
        schedule();
    }

    /* 레이아웃/상태에 실제 영향이 있는 변화만 반응 (재생 진행/가사 하이라이트 등은 무시 → 메인 스레드 부하 0) */
    const OVERLAYS = '#pl-ctx-menu,#pl-dialog-overlay,#pl-confirm-overlay,#pl-add-modal-overlay';
    const HOSTS = '#mob-nav,#bar,.mob-srch-wrap';
    const mo = new MutationObserver(recs => {
        let burst = false, once = false;
        for (const r of recs) {
            const t = r.target.nodeType === 1 ? r.target : r.target.parentElement;
            if (!t || t.id === 'bar-prog-fill') continue;
            // 박자 효과(카드 transform/shadow, 비주얼라이저 등)는 글래스 레이아웃과 무관 — closest() 비용도 아낀다
            if (t.classList && (t.classList.contains('card') || t.classList.contains('vb'))) continue;
            if (t.id === 'np' || t.classList.contains('view') || t.id === 'bar') { if (t.id === 'np') syncNpLock(); once = true; continue; }
            if (t.closest) {
                if (t.closest(OVERLAYS)) { burst = true; break; }
                if (t.closest(HOSTS)) once = true;
            }
        }
        if (burst) kick(); else if (once) kick(450);
    });
    mo.observe(document.body, {
        subtree: true, childList: true, characterData: true,
        attributes: true, attributeFilter: ['class', 'style', 'src', 'placeholder']
    });

    /* 스크롤: 위치 계산은 네이티브가 WebView.getScrollY() 로 직접 한다(같은 프레임, JS 왕복 없음).
       ※ 예전에는 scroll 이벤트마다 GlassBridge.scroll() 을 호출했는데, JavascriptInterface 호출은 렌더러 메인 스레드를
         막는 동기 IPC 라서 스크롤 프레임 드롭의 원인이 되었다 → 제거.
       여기서는 "스크롤 중인지"만 타임스탬프로 기록(호출 비용 0)해서, 스크롤 중에는 안전망 측정을 건너뛴다. */
    let lastScroll = 0;
    let settleT = 0;
    window.addEventListener('scroll', () => {
        lastScroll = performance.now();
        clearTimeout(settleT);       // 스크롤이 멈추면 딱 한 번 "확정 스크롤 값"을 네이티브에 알려 마지막 어긋남을 복구
        settleT = setTimeout(() => { try { window.GlassBridge.settled(window.scrollY || 0); } catch (e) {} }, 150);
    }, { passive: true });
    ['resize', 'orientationchange'].forEach(e => window.addEventListener(e, () => kick(400), { passive: true }));
    /* 박자 효과로 계속 일어나는 카드/비주얼라이저의 transition·animation 종료는 레이아웃을 바꾸지 않으므로
       무시한다 (재생 중 kick → 프레임마다 collect()+JSON.stringify 가 반복되던 부하 제거). */
    const BEAT_FX = '.card, #vizz, #np-pulse, #np-particles, .c-now-bar';
    ['input', 'focusin', 'focusout', 'transitionend', 'animationend']
        .forEach(ev => document.addEventListener(ev, e => {
            if ((ev === 'transitionend' || ev === 'animationend') && e.target && e.target.closest && e.target.closest(BEAT_FX)) return;
            kick(300);
        }, { capture: true, passive: true }));
    setInterval(() => {            // 안전망 (프로그램적 value 변경 등 이벤트 없는 변화) — 변화 없으면 전송 안 함
        if (window.__bgMode) return;           // 백그라운드(화면 꺼짐/홈)에서는 측정 불필요
        const now = performance.now();
        if (now - lastScroll < 250) return;   // 스크롤 중에는 DOM 측정(강제 레이아웃)을 하지 않는다
        geomDirty = true;                      // 유휴 시 기하 자가 검증 (폰트 로딩 등으로 위치가 바뀐 경우 복구)
        schedule();
    }, 1000);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => kick(300));
    window.addEventListener('load', () => kick(500));
    syncNpLock();
    kick(1200);

    /* ── Native → DOM ── */
    function fireKey(el, key) {
        el.dispatchEvent(new KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true }));
    }
    window.__glassCmd = function (c) {
        try {
            switch (c.t) {
                case 'nav': {
                    const b = document.querySelector('#mob-nav .mn-btn[data-v="' + c.v + '"]');
                    if (b) b.click();
                    break;
                }
                case 'bar': {
                    if (c.a === 'open') openNP();
                    else if (c.a === 'fav') favCur();
                    else if (c.a === 'play') togglePlay();
                    else if (c.a === 'next') nextT();
                    break;
                }
                case 'text': {
                    const el = $(c.id);
                    if (el) { el.value = c.v; el.dispatchEvent(new Event('input', { bubbles: true })); }
                    break;
                }
                case 'focus': {
                    const el = $(c.id);
                    if (!el) break;
                    if (c.on) el.focus({ preventScroll: true }); else el.blur();
                    break;
                }
                case 'enter': {
                    const el = $(c.id);
                    if (el) fireKey(el, 'Enter');
                    break;
                }
                case 'ctx': {
                    if (c.new) { const n = document.querySelector('#pl-ctx-menu .pl-ctx-new'); if (n) n.click(); }
                    else {
                        const items = document.querySelectorAll('#pl-ctx-list .pl-ctx-item');
                        if (items[c.i]) items[c.i].click();
                    }
                    break;
                }
                case 'dlg': if (c.a === 'ok') plDialogConfirm(); else plDialogCancel(); break;
                case 'cfm': if (c.a === 'ok') plConfirmOk(); else plConfirmCancel(); break;
                case 'add': {
                    if (c.a === 'close') plAddModalClose();
                    else if (c.a === 'row') {
                        const rows = document.querySelectorAll('#pl-add-modal-results .pl-modal-row');
                        const b = rows[c.i] && rows[c.i].querySelector('.pl-modal-add-btn');
                        if (b) b.click();
                    }
                    break;
                }
            }
        } catch (e) { /* 명령 실패가 앱 로직을 깨뜨리지 않도록 */ }
        kick(500);
    };
})();
