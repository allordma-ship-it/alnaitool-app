/* AuraStream portal – auto-fill pairing + playlist management. No framework, no inline handlers (CSP friendly). */
(() => {
  'use strict';

  const API = '/api/v1';
  const SESSION_KEY = 'aura.session';
  const LANG_KEY = 'aura.lang';

  // ------------------------------------------------------------------ i18n
  const STRINGS = {
    en: {
      tagline: 'Remote playlist manager',
      connectTitle: 'Connect your TV',
      connectHint: 'Enter the MAC address and Device Key shown on your TV screen, or scan the QR code.',
      macLabel: 'MAC address',
      keyLabel: 'Device Key',
      connectBtn: 'Connect',
      connecting: 'Connecting…',
      connectedAs: 'Connected device',
      logout: 'Sign out',
      addTitle: 'Add a playlist',
      tabUrl: 'M3U URL',
      tabFile: 'File',
      tabXtream: 'Xtream Codes',
      namePh: 'Playlist name (optional)',
      epgPh: 'XMLTV EPG URL (optional)',
      userPh: 'Username',
      passPh: 'Password',
      save: 'Send to TV',
      upload: 'Upload to TV',
      fileHint: 'Tap to choose an .m3u / .m3u8 file',
      listTitle: 'Playlists on this TV',
      refresh: 'Refresh',
      empty: 'No playlists yet. Add one above – your TV picks it up on the next refresh.',
      syncHint: 'Your TV checks for changes automatically. To load them right away press Refresh on the TV home screen.',
      delete: 'Delete',
      confirmDelete: 'Delete this playlist from your TV?',
      saved: 'Playlist sent to your TV ✔',
      deleted: 'Playlist deleted',
      invalidMac: 'Please enter a valid MAC address (12 hex digits).',
      invalidKey: 'The Device Key has exactly 6 digits.',
      invalidUrl: 'Please enter a valid http(s) URL.',
      missingXtream: 'Host, username and password are required.',
      missingFile: 'Please choose a playlist file first.',
      sessionExpired: 'Your session expired – please connect again.',
      network: 'Network error – check your connection.',
      channels: 'channels',
      trial: (d) => `Trial · ${d} day${d === 1 ? '' : 's'} left`,
      active: 'Active',
      expired: 'Expired',
    },
    ar: {
      tagline: 'إدارة قوائم التشغيل عن بُعد',
      connectTitle: 'اربط جهاز التلفاز',
      connectHint: 'أدخل عنوان MAC ومفتاح الجهاز الظاهرين على شاشة التلفاز، أو امسح رمز QR.',
      macLabel: 'عنوان MAC',
      keyLabel: 'مفتاح الجهاز',
      connectBtn: 'اتصال',
      connecting: 'جارٍ الاتصال…',
      connectedAs: 'الجهاز المتصل',
      logout: 'تسجيل الخروج',
      addTitle: 'إضافة قائمة تشغيل',
      tabUrl: 'رابط M3U',
      tabFile: 'ملف',
      tabXtream: 'Xtream Codes',
      namePh: 'اسم القائمة (اختياري)',
      epgPh: 'رابط دليل البرامج XMLTV (اختياري)',
      userPh: 'اسم المستخدم',
      passPh: 'كلمة المرور',
      save: 'إرسال إلى التلفاز',
      upload: 'رفع إلى التلفاز',
      fileHint: 'اضغط لاختيار ملف ‎.m3u / .m3u8',
      listTitle: 'قوائم التشغيل على هذا التلفاز',
      refresh: 'تحديث',
      empty: 'لا توجد قوائم بعد. أضف قائمة أعلاه وسيقوم التلفاز بتحميلها عند التحديث التالي.',
      syncHint: 'يتحقق التلفاز من التغييرات تلقائيًا. لتحميلها فورًا اضغط «تحديث» في الشاشة الرئيسية للتلفاز.',
      delete: 'حذف',
      confirmDelete: 'هل تريد حذف هذه القائمة من التلفاز؟',
      saved: 'تم إرسال القائمة إلى التلفاز ✔',
      deleted: 'تم حذف القائمة',
      invalidMac: 'يرجى إدخال عنوان MAC صالح (12 خانة ست عشرية).',
      invalidKey: 'مفتاح الجهاز مكوّن من 6 أرقام.',
      invalidUrl: 'يرجى إدخال رابط http(s) صالح.',
      missingXtream: 'الخادم واسم المستخدم وكلمة المرور مطلوبة.',
      missingFile: 'يرجى اختيار ملف القائمة أولاً.',
      sessionExpired: 'انتهت الجلسة – يرجى الاتصال مجددًا.',
      network: 'خطأ في الشبكة – تحقّق من اتصالك.',
      channels: 'قناة',
      trial: (d) => `فترة تجريبية · متبقٍ ${d} يوم`,
      active: 'مفعّل',
      expired: 'منتهي',
    },
  };

  const storage = {
    get(store, key) {
      try { return window[store].getItem(key); } catch { return null; }
    },
    set(store, key, value) {
      try { value == null ? window[store].removeItem(key) : window[store].setItem(key, value); } catch { /* private mode */ }
    },
  };

  let lang = storage.get('localStorage', LANG_KEY) || ((navigator.language || 'en').toLowerCase().startsWith('ar') ? 'ar' : 'en');
  const t = (key, ...args) => {
    const v = (STRINGS[lang] && STRINGS[lang][key]) ?? STRINGS.en[key] ?? key;
    return typeof v === 'function' ? v(...args) : v;
  };

  function applyLanguage() {
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
    document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
    document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => { el.placeholder = t(el.dataset.i18nPlaceholder); });
    $('#langToggle').textContent = lang === 'ar' ? 'English' : 'العربية';
    if (state.device) renderLicense(state.device);
    if (state.playlists) renderPlaylists(state.playlists);
  }

  // ----------------------------------------------------------------- utils
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const state = { token: null, device: null, playlists: null };

  function normalizeMac(raw) {
    const hex = String(raw || '').replace(/[^0-9a-f]/gi, '').toUpperCase().slice(0, 12);
    return hex.match(/.{1,2}/g)?.join(':') ?? '';
  }
  const isValidMac = (mac) => /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(mac);
  const isValidUrl = (value) => {
    try { const u = new URL(value); return u.protocol === 'http:' || u.protocol === 'https:'; } catch { return false; }
  };

  let toastTimer;
  function toast(message, kind = 'info') {
    const el = $('#toast');
    el.textContent = message;
    el.className = 'fixed inset-x-4 bottom-6 z-50 mx-auto max-w-md rounded-xl border px-4 py-3 text-sm shadow-lg ' +
      (kind === 'error' ? 'border-red-500/40 bg-red-950/90 text-red-100' : 'border-cyan-400/40 bg-surface text-white');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.add('hidden-important'), 4000);
  }

  function setBusy(button, busy, busyLabel) {
    if (!button) return;
    if (busy) {
      button.dataset.label = button.textContent;
      button.textContent = busyLabel || '…';
      button.disabled = true;
    } else {
      button.textContent = button.dataset.label || button.textContent;
      button.disabled = false;
    }
  }

  async function api(path, { method = 'GET', body, auth = true } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && state.token) headers.Authorization = `Bearer ${state.token}`;
    let res;
    try {
      res = await fetch(API + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch {
      throw new Error(t('network'));
    }
    if (res.status === 204) return null;
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && auth) {
      logout();
      throw new Error(t('sessionExpired'));
    }
    if (!res.ok) throw new Error(data.message || `HTTP ${res.status}`);
    return data;
  }

  // --------------------------------------------------------------- session
  function saveSession(session) {
    state.token = session?.token ?? null;
    storage.set('sessionStorage', SESSION_KEY, session ? JSON.stringify(session) : null);
  }

  function restoreSession() {
    try {
      const s = JSON.parse(storage.get('sessionStorage', SESSION_KEY) || 'null');
      if (s && s.token && new Date(s.expiresAt).getTime() > Date.now()) {
        state.token = s.token;
        return true;
      }
    } catch { /* ignore */ }
    return false;
  }

  function logout() {
    saveSession(null);
    state.device = null;
    state.playlists = null;
    $('#manageCard').classList.add('hidden-important');
    $('#loginCard').classList.remove('hidden-important');
  }

  async function login(mac, deviceKey) {
    const btn = $('#loginBtn');
    setBusy(btn, true, t('connecting'));
    try {
      const res = await api('/device/verify', { method: 'POST', body: { mac, deviceKey }, auth: false });
      saveSession({ token: res.token, expiresAt: res.expiresAt });
      state.device = res.device;
      await showManager();
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      setBusy(btn, false);
    }
  }

  async function showManager() {
    $('#loginCard').classList.add('hidden-important');
    $('#manageCard').classList.remove('hidden-important');
    await loadPlaylists();
  }

  // ------------------------------------------------------------ rendering
  function renderLicense(device) {
    $('#deviceMac').textContent = device.mac;
    const badge = $('#licenseBadge');
    const styles = {
      trial: ['bg-amber-400/15 text-amber-300', t('trial', device.daysLeft ?? 0)],
      active: ['bg-emerald-400/15 text-emerald-300', t('active')],
      expired: ['bg-red-500/15 text-red-300', t('expired')],
    };
    const [cls, label] = styles[device.status] || styles.trial;
    badge.className = `shrink-0 rounded-full px-3 py-1 text-xs font-semibold ${cls}`;
    badge.textContent = label;
  }

  function renderPlaylists(list) {
    const ul = $('#playlistList');
    ul.replaceChildren();
    $('#emptyState').classList.toggle('hidden-important', list.length > 0);
    const tpl = $('#playlistItem');
    for (const p of list) {
      const node = tpl.content.firstElementChild.cloneNode(true);
      $('[data-slot="type"]', node).textContent = { m3u_url: 'URL', file: 'M3U', xtream: 'XC' }[p.sourceType] || '?';
      $('[data-slot="name"]', node).textContent = p.name;
      const meta = p.sourceType === 'xtream'
        ? `${p.xtream?.host ?? ''} · ${p.xtream?.username ?? ''}`
        : p.sourceType === 'file'
          ? `${p.channelCount ?? 0} ${t('channels')}`
          : p.sourceUrl ?? '';
      $('[data-slot="meta"]', node).textContent = meta;
      const del = $('[data-slot="delete"]', node);
      del.textContent = t('delete');
      del.addEventListener('click', () => deletePlaylist(p.id));
      ul.appendChild(node);
    }
  }

  async function loadPlaylists() {
    try {
      const res = await api('/playlists');
      state.device = res.device;
      state.playlists = res.playlists;
      renderLicense(res.device);
      renderPlaylists(res.playlists);
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  async function deletePlaylist(id) {
    if (!window.confirm(t('confirmDelete'))) return;
    try {
      await api(`/playlists/${encodeURIComponent(id)}`, { method: 'DELETE' });
      toast(t('deleted'));
      await loadPlaylists();
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  // ---------------------------------------------------------------- forms
  function selectTab(name) {
    $$('[role="tab"]').forEach((tab) => tab.setAttribute('aria-selected', String(tab.dataset.tab === name)));
    $$('[data-panel]').forEach((panel) => panel.classList.toggle('hidden-important', panel.dataset.panel !== name));
  }

  function bindForms() {
    const macInput = $('#mac');
    macInput.addEventListener('input', () => {
      const pos = macInput.value.length;
      macInput.value = normalizeMac(macInput.value);
      if (pos >= macInput.value.length) macInput.setSelectionRange(macInput.value.length, macInput.value.length);
    });
    const keyInput = $('#deviceKey');
    keyInput.addEventListener('input', () => { keyInput.value = keyInput.value.replace(/\D/g, '').slice(0, 6); });

    $('#loginForm').addEventListener('submit', (e) => {
      e.preventDefault();
      const mac = normalizeMac(macInput.value);
      const key = keyInput.value.trim();
      if (!isValidMac(mac)) return toast(t('invalidMac'), 'error');
      if (!/^\d{6}$/.test(key)) return toast(t('invalidKey'), 'error');
      login(mac, key);
    });

    $$('[role="tab"]').forEach((tab) => tab.addEventListener('click', () => selectTab(tab.dataset.tab)));
    $('#logoutBtn').addEventListener('click', logout);
    $('#refreshBtn').addEventListener('click', loadPlaylists);
    $('#langToggle').addEventListener('click', () => {
      lang = lang === 'ar' ? 'en' : 'ar';
      storage.set('localStorage', LANG_KEY, lang);
      applyLanguage();
    });

    $('#m3uForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.currentTarget;
      const data = Object.fromEntries(new FormData(form));
      if (!isValidUrl(data.sourceUrl)) return toast(t('invalidUrl'), 'error');
      if (data.epgUrl && !isValidUrl(data.epgUrl)) return toast(t('invalidUrl'), 'error');
      await submitJson(form, {
        sourceType: 'm3u_url',
        name: data.name || undefined,
        sourceUrl: data.sourceUrl.trim(),
        epgUrl: data.epgUrl ? data.epgUrl.trim() : undefined,
      });
    });

    $('#xtreamForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.currentTarget;
      const data = Object.fromEntries(new FormData(form));
      if (!data.host?.trim() || !data.username?.trim() || !data.password) return toast(t('missingXtream'), 'error');
      await submitJson(form, {
        sourceType: 'xtream',
        name: data.name || undefined,
        xtream: { host: data.host.trim(), username: data.username.trim(), password: data.password },
      });
    });

    const fileInput = $('#fileInput');
    fileInput.addEventListener('change', () => {
      $('#fileLabel').textContent = fileInput.files[0]?.name || t('fileHint');
    });
    const drop = $('#dropZone');
    ['dragenter', 'dragover'].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('border-neon'); }));
    ['dragleave', 'drop'].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove('border-neon'); }));
    drop.addEventListener('drop', (e) => {
      if (e.dataTransfer?.files?.length) {
        fileInput.files = e.dataTransfer.files;
        fileInput.dispatchEvent(new Event('change'));
      }
    });

    $('#fileForm').addEventListener('submit', (e) => {
      e.preventDefault();
      const form = e.currentTarget;
      const file = fileInput.files[0];
      if (!file) return toast(t('missingFile'), 'error');
      uploadFile(form, file);
    });
  }

  async function submitJson(form, body) {
    const btn = $('button[type="submit"]', form);
    setBusy(btn, true);
    try {
      await api('/playlists', { method: 'POST', body });
      form.reset();
      toast(t('saved'));
      await loadPlaylists();
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      setBusy(btn, false);
    }
  }

  function uploadFile(form, file) {
    const btn = $('button[type="submit"]', form);
    const bar = $('#uploadProgress');
    const fill = bar.firstElementChild;
    const fd = new FormData();
    const name = $('input[name="name"]', form).value.trim();
    if (name) fd.append('name', name);
    fd.append('file', file, file.name);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${API}/playlists/upload`);
    xhr.setRequestHeader('Authorization', `Bearer ${state.token}`);
    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable) fill.style.width = `${Math.round((e.loaded / e.total) * 100)}%`;
    });
    xhr.addEventListener('loadend', async () => {
      setBusy(btn, false);
      bar.classList.add('hidden-important');
      fill.style.width = '0';
      let data = {};
      try { data = JSON.parse(xhr.responseText || '{}'); } catch { /* ignore */ }
      if (xhr.status === 401) { logout(); return toast(t('sessionExpired'), 'error'); }
      if (xhr.status === 0) return toast(t('network'), 'error');
      if (xhr.status >= 400) return toast(data.message || `HTTP ${xhr.status}`, 'error');
      form.reset();
      $('#fileLabel').textContent = t('fileHint');
      toast(t('saved'));
      await loadPlaylists();
    });
    setBusy(btn, true);
    bar.classList.remove('hidden-important');
    xhr.send(fd);
  }

  // ------------------------------------------------------------------ boot
  async function boot() {
    bindForms();
    applyLanguage();

    // QR code deep link: /?mac=AA:BB:..&key=123456 -> auto-fill (+ auto-connect).
    const params = new URLSearchParams(window.location.search);
    const qMac = normalizeMac(params.get('mac'));
    const qKey = (params.get('key') || '').replace(/\D/g, '').slice(0, 6);
    if (qMac) $('#mac').value = qMac;
    if (qKey) $('#deviceKey').value = qKey;
    if (params.has('mac') || params.has('key')) {
      // Keep the Device Key out of browser history / shared screenshots.
      window.history.replaceState(null, '', window.location.pathname);
    }

    if (isValidMac(qMac) && /^\d{6}$/.test(qKey)) {
      saveSession(null);
      await login(qMac, qKey);
    } else if (restoreSession()) {
      await showManager();
    } else if (qMac) {
      $('#deviceKey').focus();
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
