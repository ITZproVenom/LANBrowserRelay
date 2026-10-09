(function () {
  let historyStack = [];
  let historyIndex = -1;
  let currentUrl = null;
  const $ = (id) => document.getElementById(id);
  const urlInput = $('urlInput');
  const viewer = $('viewer');
  const homePanel = $('homePanel');
  const loadingOverlay = $('loadingOverlay');
  const connIndicator = $('connIndicator');
  const pageTitle = $('pageTitle');
  const pageUrl = $('pageUrl');
  const limitDisplay = $('limitDisplay');
  const dlPanel = $('dlPanel');
  const dlList = $('dlList');

  function show(el) { el.classList.remove('hidden'); }
  function hide(el) { el.classList.add('hidden'); }

  function resolveInput(raw) {
    const input = String(raw || '').trim();
    if (!input) return null;
    if (/^https?:\/\//i.test(input)) return input;
    if (input.includes('.') && !input.includes(' ')) return 'https://' + input;
    return 'https://www.google.com/search?q=' + encodeURIComponent(input) + '&hl=en';
  }

  function formatBytes(value) {
    const n = Number(value) || 0;
    if (n < 1000) return n + ' B';
    if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + ' KB';
    return (n / 1000000).toFixed(n < 10000000 ? 2 : 1) + ' MB';
  }

  function getOrCreateRow(item) {
    const id = String(item.id);
    let row = Array.from(dlList.children).find((el) => el.dataset.downloadId === id);
    if (row) return row;

    row = document.createElement('div');
    row.className = 'dl-item';
    row.dataset.downloadId = id;

    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = item.filename || 'Download';

    const meta = document.createElement('div');
    meta.className = 'meta';

    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'cancel';
    cancel.textContent = 'Cancel';
    cancel.addEventListener('click', async () => {
      cancel.disabled = true;
      try {
        const response = await fetch('/api/cancel?id=' + encodeURIComponent(id), { cache: 'no-store' });
        const result = await response.json().catch(() => ({}));
        meta.textContent = result.ok ? 'Cancellation requested' : (result.error || 'Transfer is no longer active');
      } catch (_) {
        meta.textContent = 'Could not contact the TV';
      }
    });

    row.append(name, meta, cancel);
    dlList.prepend(row);
    while (dlList.children.length > 25) dlList.lastElementChild.remove();
    return row;
  }

  function renderDownload(item) {
    if (!item || !item.id) return;
    const row = getOrCreateRow(item);
    const name = row.querySelector('.name');
    const meta = row.querySelector('.meta');
    const cancel = row.querySelector('.cancel');
    if (item.filename) name.textContent = item.filename;

    const bytes = Number(item.bytes) || 0;
    const total = item.contentLength == null ? null : Number(item.contentLength);
    const status = String(item.status || 'STARTING');
    let line = status.replaceAll('_', ' ').toLowerCase();

    if (status === 'STREAMING' || status === 'COMPLETED' || status === 'LIMIT_EXCEEDED' || status === 'FAILED') {
      line += ' · ' + formatBytes(bytes);
      if (total != null && total > 0) line += ' / ' + formatBytes(total);
      if (status === 'STREAMING' && Number(item.speed) > 0) line += ' · ' + formatBytes(item.speed) + '/s';
    }
    if (item.error) line += ' · ' + String(item.error);
    meta.textContent = line;

    const running = status === 'STARTING' || status === 'STREAMING';
    cancel.hidden = !running;
    cancel.disabled = !running;
  }

  async function checkStatus() {
    try {
      const response = await fetch('/api/status', { cache: 'no-store' });
      if (!response.ok) throw new Error('Status unavailable');
      const data = await response.json();
      connIndicator.className = 'dot ok';
      if (data.maxDownloadBytes) limitDisplay.textContent = Math.floor(data.maxDownloadBytes / 1000000) + ' MB';
      if (Array.isArray(data.active)) data.active.forEach(renderDownload);
      return true;
    } catch (_) {
      connIndicator.className = 'dot err';
      return false;
    }
  }

  function showHome() {
    hide(viewer);
    show(homePanel);
    pageTitle.textContent = 'Home';
    pageUrl.textContent = '';
    urlInput.value = '';
    currentUrl = null;
  }

  function showLoading(on) {
    if (on) show(loadingOverlay);
    else hide(loadingOverlay);
  }

  function navigate(raw) {
    const target = resolveInput(raw || urlInput.value);
    if (target) loadUrl(target);
  }

  function loadUrl(url, pushHistory = true) {
    showLoading(true);
    hide(homePanel);
    show(viewer);
    viewer.src = '/browse?url=' + encodeURIComponent(url);
    currentUrl = url;
    urlInput.value = url;
    pageUrl.textContent = url;
    pageTitle.textContent = 'Loading…';
    if (pushHistory) {
      historyStack = historyStack.slice(0, historyIndex + 1);
      historyStack.push(url);
      historyIndex = historyStack.length - 1;
    }
  }

  viewer.addEventListener('load', () => {
    showLoading(false);
    pageTitle.textContent = 'Page loaded';
  });

  $('navForm').addEventListener('submit', (event) => {
    event.preventDefault();
    navigate();
  });

  $('homeSearch').addEventListener('submit', (event) => {
    event.preventDefault();
    navigate($('homeQuery').value);
  });

  $('backBtn').addEventListener('click', () => {
    if (historyIndex > 0) {
      historyIndex--;
      loadUrl(historyStack[historyIndex], false);
    } else {
      showHome();
    }
  });

  $('fwdBtn').addEventListener('click', () => {
    if (historyIndex < historyStack.length - 1) {
      historyIndex++;
      loadUrl(historyStack[historyIndex], false);
    }
  });

  $('reloadBtn').addEventListener('click', () => {
    if (currentUrl) loadUrl(currentUrl, false);
  });

  $('homeBtn').addEventListener('click', showHome);
  $('downloadCurrentBtn').addEventListener('click', () => {
    const target = resolveInput(currentUrl || urlInput.value);
    if (target) window.startDownload(target);
  });

  window.startDownload = function (url, filename) {
    const target = resolveInput(url);
    if (!target) return;

    const id = (window.crypto && typeof window.crypto.randomUUID === 'function')
      ? window.crypto.randomUUID()
      : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    const inferredName = target.split('/').pop().split('?')[0] || 'download.bin';
    const name = filename || inferredName;
    const query = new URLSearchParams({ url: target, id: id });
    if (filename) query.set('filename', filename);

    const link = document.createElement('a');
    link.href = '/api/download?' + query.toString();
    link.download = name;
    link.hidden = true;
    document.body.appendChild(link);
    link.click();
    link.remove();

    renderDownload({
      id: id,
      url: target,
      filename: name,
      bytes: 0,
      contentLength: null,
      speed: 0,
      status: 'STARTING'
    });
    show(dlPanel);
    checkStatus();
  };

  $('dlPanelBtn').addEventListener('click', () => dlPanel.classList.toggle('hidden'));
  $('closeDlPanel').addEventListener('click', () => hide(dlPanel));

  checkStatus();
  window.setInterval(checkStatus, 1200);
  showHome();
})();
