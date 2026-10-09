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
  async function checkStatus() {
    try {
      const res = await fetch('/api/status');
      if (res.ok) {
        const data = await res.json();
        connIndicator.className = 'dot ok';
        if (data.maxDownloadBytes) limitDisplay.textContent = Math.round(data.maxDownloadBytes / 1e6) + ' MB';
        return true;
      }
    } catch (_) {}
    connIndicator.className = 'dot err';
    return false;
  }
  function showHome() {
    hide(viewer); show(homePanel);
    pageTitle.textContent = 'Home'; pageUrl.textContent = ''; urlInput.value = ''; currentUrl = null;
  }
  function showLoading(on) { if (on) show(loadingOverlay); else hide(loadingOverlay); }
  function navigate(raw) {
    let input = (raw || urlInput.value || '').trim();
    if (!input) return;
    let target;
    if (/^https?:\/\//i.test(input)) target = input;
    else if (input.includes('.') && !input.includes(' ')) target = 'https://' + input;
    else target = 'https://www.google.com/search?q=' + encodeURIComponent(input) + '&hl=en';
    loadUrl(target);
  }
  function loadUrl(url, pushHistory = true) {
    showLoading(true); hide(homePanel); show(viewer);
    viewer.src = '/browse?url=' + encodeURIComponent(url);
    currentUrl = url; urlInput.value = url; pageUrl.textContent = url; pageTitle.textContent = 'Loading…';
    if (pushHistory) { historyStack = historyStack.slice(0, historyIndex + 1); historyStack.push(url); historyIndex = historyStack.length - 1; }
  }
  viewer.addEventListener('load', () => { showLoading(false); pageTitle.textContent = 'Page loaded'; });
  $('navForm').addEventListener('submit', (e) => { e.preventDefault(); navigate(); });
  $('homeSearch').addEventListener('submit', (e) => { e.preventDefault(); const q = $('homeQuery').value.trim(); if (q) navigate(q); });
  $('backBtn').addEventListener('click', () => { if (historyIndex > 0) { historyIndex--; loadUrl(historyStack[historyIndex], false); } else showHome(); });
  $('fwdBtn').addEventListener('click', () => { if (historyIndex < historyStack.length - 1) { historyIndex++; loadUrl(historyStack[historyIndex], false); } });
  $('reloadBtn').addEventListener('click', () => { if (currentUrl) loadUrl(currentUrl, false); });
  $('homeBtn').addEventListener('click', showHome);
  window.startDownload = function (url, filename) {
    const a = document.createElement('a');
    a.href = '/api/download?url=' + encodeURIComponent(url) + (filename ? '&filename=' + encodeURIComponent(filename) : '');
    a.download = filename || ''; a.style.display = 'none'; document.body.appendChild(a); a.click(); document.body.removeChild(a);
    const item = document.createElement('div'); item.className = 'dl-item';
    item.innerHTML = '<div class="name">' + (filename || url.split('/').pop() || 'download') + '</div><div class="meta">Started – streaming via TV</div>';
    dlList.prepend(item); show(dlPanel);
  };
  $('dlPanelBtn').addEventListener('click', () => dlPanel.classList.toggle('hidden'));
  $('closeDlPanel').addEventListener('click', () => hide(dlPanel));
  checkStatus(); setInterval(checkStatus, 8000); showHome();
})();
