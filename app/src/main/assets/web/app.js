(() => {
  const $ = id => document.getElementById(id);
  const address = $('address');
  const viewer = $('viewer');
  const tabs = new Map([[1, { id: 1, title: 'New tab', url: '', history: [], index: -1 }]]);
  const transfers = new Map();
  let nextTabId = 2;
  let activeTabId = 1;
  let currentUrl = '';
  const csrfToken = document.querySelector('meta[name="relay-csrf-token"]')?.content || '';
  const apiHeaders = { 'X-Relay-CSRF': csrfToken };
  const contentPort = Number(document.querySelector('meta[name="relay-content-port"]')?.content) || 8081;
  const relayContentOrigin = (() => { const origin = new URL(location.href); origin.port = String(contentPort); return origin.origin; })();
  let frameMessageWindow = Date.now();
  let frameMessageCount = 0;

  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[ch]));

  function activeTab() { return tabs.get(activeTabId); }

  function parseTarget(raw) {
    const input = String(raw || '').trim();
    if (!input) return '';
    if (/^https?:\/\//i.test(input)) return input;
    if (/^[a-z][a-z0-9+.-]*:/i.test(input)) return input;
    if (input.includes('.') && !/\s/.test(input)) return 'https://' + input;
    return 'https://www.google.com/search?q=' + encodeURIComponent(input) + '&hl=en';
  }

  function hostname(url) {
    try { return new URL(url).hostname.replace(/^www\./, '').slice(0, 24); }
    catch (_) { return 'Web page'; }
  }

  function renderTabs() {
    const container = $('tabs');
    container.innerHTML = [...tabs.values()].map(tab =>
      '<button class="tab ' + (tab.id === activeTabId ? 'active' : '') +
      '" data-tab="' + tab.id + '">' + escapeHtml(tab.title || 'New tab') + '</button>'
    ).join('');
    container.querySelectorAll('[data-tab]').forEach(button => {
      button.addEventListener('click', () => switchTab(Number(button.dataset.tab)));
    });
  }

  function showHomeForActiveTab() {
    const tab = activeTab();
    tab.url = '';
    tab.title = 'New tab';
    currentUrl = '';
    address.value = '';
    $('homeview').classList.remove('hidden');
    $('browserview').classList.add('hidden');
    $('summary').textContent = 'Ready when you are';
    renderTabs();
  }

  function navigate(raw, pushHistory = true) {
    const target = parseTarget(raw);
    if (!target) return;
    if (!/^https?:\/\//i.test(target)) {
      showError('Only HTTP and HTTPS websites are supported.');
      return;
    }
    loadUrl(target, pushHistory);
  }

  function loadUrl(url, pushHistory = true) {
    const tab = activeTab();
    if (pushHistory) {
      tab.history = tab.history.slice(0, tab.index + 1);
      tab.history.push(url);
      tab.index = tab.history.length - 1;
    }
    tab.url = url;
    tab.title = hostname(url);
    currentUrl = url;
    address.value = url;
    $('homeview').classList.add('hidden');
    $('browserview').classList.remove('hidden');
    $('loader').classList.remove('hidden');
    $('pageerror').classList.add('hidden');
    $('pageurl').textContent = url;
    $('pagetitle').textContent = 'Loading…';
    viewer.src = relayContentOrigin + '/browse?url=' + encodeURIComponent(url);
    renderTabs();
  }

  function switchTab(id) {
    if (!tabs.has(id)) return;
    activeTabId = id;
    const tab = activeTab();
    if (tab.url) loadUrl(tab.url, false);
    else showHomeForActiveTab();
    renderTabs();
  }

  function showError(message) {
    $('loader').classList.add('hidden');
    $('pageerror').textContent = message;
    $('pageerror').classList.remove('hidden');
  }

  function handleFrameMessage(event) {
    // The frame's opaque origin serializes as "null". Verify both it and the
    // exact frame WindowProxy before treating a message as a clicked link.
    if (event.source !== viewer.contentWindow || event.origin !== 'null') return;
    const message = event.data;
    if (!message || typeof message !== 'object' ||
        message.type !== 'lanbrowserrelay:navigate' ||
        typeof message.url !== 'string' || message.url.length > 8192 ||
        Object.keys(message).length !== 2 || !Object.hasOwn(message, 'url') || !Object.hasOwn(message, 'type')) return;

    const now = Date.now();
    if (now - frameMessageWindow >= 60_000) { frameMessageWindow = now; frameMessageCount = 0; }
    if (++frameMessageCount > 30) return;
    let target;
    try { target = new URL(message.url); }
    catch (_) { return; }
    if (target.protocol !== 'http:' && target.protocol !== 'https:') return;
    if (target.origin === location.origin) return;
    // The bridge can only request ordinary navigation. It has no download,
    // cancellation, API, or UI-mutation command; the user starts downloads.
    navigate(target.href);
  }

  window.addEventListener('message', handleFrameMessage);

  function filenameFromUrl(url) {
    try {
      const part = new URL(url).pathname.split('/').pop() || 'download.bin';
      return decodeURIComponent(part);
    } catch (_) { return 'download.bin'; }
  }

  function startDownload(url, filename) {
    const id = (window.crypto && crypto.randomUUID)
      ? crypto.randomUUID()
      : Date.now().toString(36) + Math.random().toString(36).slice(2);
    const transfer = {
      id, url, filename: filename || filenameFromUrl(url),
      bytes: 0, total: null, speed: 0, status: 'STARTING', error: null
    };
    transfers.set(id, transfer);
    renderDownloads();

    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '/api/download';
    form.hidden = true;
    for (const [name, value] of Object.entries({
      id, url, filename: transfer.filename, _csrf: csrfToken
    })) {
      const field = document.createElement('input');
      field.type = 'hidden';
      field.name = name;
      field.value = value;
      form.appendChild(field);
    }
    document.body.appendChild(form);
    form.submit();
    form.remove();
    $('downloads').classList.remove('hidden');
  }

  function formatBytes(value) {
    if (value == null) return '—';
    if (value < 1000) return value + ' B';
    if (value < 1e6) return (value / 1000).toFixed(1) + ' kB';
    if (value < 1e9) return (value / 1e6).toFixed(2) + ' MB';
    return (value / 1e9).toFixed(2) + ' GB';
  }

  function renderDownloads() {
    const all = [...transfers.values()].reverse();
    $('activeBadge').textContent = all.filter(t =>
      t.status === 'STARTING' || t.status === 'STREAMING'
    ).length;
    $('downloadList').innerHTML = all.length ? all.map(t => {
      const percent = t.total ? Math.min(100, t.bytes * 100 / t.total) :
        (t.status === 'STREAMING' ? 25 : 0);
      const details = t.status === 'STREAMING'
        ? formatBytes(t.bytes) + (t.total ? ' / ' + formatBytes(t.total) : '') + ' · ' + formatBytes(t.speed) + '/s'
        : t.status === 'COMPLETED' ? 'Complete · ' + formatBytes(t.bytes)
        : (t.error || t.status);
      const cancel = t.status === 'STARTING' || t.status === 'STREAMING'
        ? '<button class="cancel" data-cancel="' + escapeHtml(t.id) + '">Cancel</button>'
        : '';
      return '<div class="transfer"><div class="transferrow"><b class="transfername">' +
        escapeHtml(t.filename) + '</b><span>' + escapeHtml(t.status) +
        '</span></div><div class="meta">' + escapeHtml(details) +
        '</div><div class="progress"><span style="width:' + percent +
        '%"></span></div>' + cancel + '</div>';
    }).join('') : '<p class="empty">Nothing downloading yet.</p>';
  }

  async function pollStatus() {
    try {
      const response = await fetch('/api/status', { cache: 'no-store', headers: apiHeaders });
      if (!response.ok) throw new Error('status request failed');
      const status = await response.json();
      $('connection').className = 'ok';
      $('connection').innerHTML = '<i></i> TV connected';
      $('summary').textContent = status.activeCount
        ? status.activeCount + ' active transfer(s)' : 'Relay ready';
      (status.downloads || []).forEach(serverTransfer => {
        if (transfers.has(serverTransfer.id)) {
          Object.assign(transfers.get(serverTransfer.id), serverTransfer);
        }
      });
      renderDownloads();
    } catch (_) {
      $('connection').className = 'error';
      $('connection').innerHTML = '<i></i> TV connection lost';
      $('summary').textContent = 'Connect to the same LAN';
    }
    setTimeout(pollStatus, 700);
  }

  $('navform').addEventListener('submit', event => {
    event.preventDefault();
    navigate(address.value);
  });
  $('searchform').addEventListener('submit', event => {
    event.preventDefault();
    navigate($('query').value);
  });
  $('back').addEventListener('click', () => {
    const tab = activeTab();
    if (tab.index > 0) {
      tab.index--;
      loadUrl(tab.history[tab.index], false);
    } else showHomeForActiveTab();
  });
  $('forward').addEventListener('click', () => {
    const tab = activeTab();
    if (tab.index < tab.history.length - 1) {
      tab.index++;
      loadUrl(tab.history[tab.index], false);
    }
  });
  $('reload').addEventListener('click', () => { if (currentUrl) loadUrl(currentUrl, false); });
  $('home').addEventListener('click', showHomeForActiveTab);
  $('downloadCurrent').addEventListener('click', () => {
    if (currentUrl) startDownload(currentUrl, filenameFromUrl(currentUrl));
  });
  $('downloadToggle').addEventListener('click', () => $('downloads').classList.toggle('hidden'));
  $('closeDownloads').addEventListener('click', () => $('downloads').classList.add('hidden'));
  $('newtab').addEventListener('click', () => {
    const id = nextTabId++;
    tabs.set(id, { id, title: 'New tab', url: '', history: [], index: -1 });
    activeTabId = id;
    showHomeForActiveTab();
    renderTabs();
  });
  document.querySelectorAll('.shortcuts button').forEach(button => {
    button.addEventListener('click', () => navigate(button.dataset.url));
  });
  $('downloadList').addEventListener('click', async event => {
    const button = event.target.closest('[data-cancel]');
    if (!button) return;
    try {
      await fetch('/api/cancel', {
        method: 'POST', cache: 'no-store', headers: {
          ...apiHeaders, 'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: new URLSearchParams({ id: button.dataset.cancel })
      });
    } catch (_) {}
  });

  viewer.addEventListener('load', () => {
    $('loader').classList.add('hidden');
    // The sandbox intentionally makes the document cross-origin to this UI.
    $('pagetitle').textContent = currentUrl ? hostname(currentUrl) : 'Page loaded';
  });

  viewer.addEventListener('error', () => {
    $('loader').classList.add('hidden');
    showError('This page could not be loaded through the TV relay.');
  });

  renderTabs();
  showHomeForActiveTab();
  pollStatus();
})();
