(() => {
  const $ = id => document.getElementById(id);
  const state = { history: [], index: -1, current: "", downloads: new Map(), toastTimer: null };
  const viewer = $("viewer");
  const address = $("addressInput");
  function toast(message) {
    const el = $("toast");
    el.textContent = message;
    el.classList.add("visible");
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => el.classList.remove("visible"), 2600);
  }
  function loading(value) {
    $("loadProgress").classList.toggle("active", value);
    $("pageState").textContent = value ? "Loading" : "Ready";
  }
  function home() {
    $("homeView").classList.remove("hidden");
    $("browserView").classList.add("hidden");
    address.value = "";
    state.current = "";
    loading(false);
  }
  function normalize(raw) {
    const value = String(raw || "").trim();
    if (!value) return "";
    if (/^https?:\/\//i.test(value)) return value;
    if (/^[^\s.]+\.[^\s.]+/.test(value) && !value.includes(" ")) return "https://" + value;
    return "https://www.google.com/search?q=" + encodeURIComponent(value) + "&hl=en";
  }
  function navigate(raw, add = true) {
    const url = normalize(raw || address.value);
    if (!url) return;
    state.current = url;
    address.value = url;
    $("pageUrl").textContent = url;
    $("homeView").classList.add("hidden");
    $("browserView").classList.remove("hidden");
    loading(true);
    viewer.src = "/browse?url=" + encodeURIComponent(url);
    if (add) {
      state.history = state.history.slice(0, state.index + 1);
      state.history.push(url);
      state.index = state.history.length - 1;
    }
  }
  function download(url, filename) {
    if (!url) return;
    const id = "dl" + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
    const params = new URLSearchParams({ url: url, id: id });
    if (filename) params.set("filename", filename);
    const a = document.createElement("a");
    a.href = "/api/download?" + params.toString();
    a.style.display = "none";
    document.body.appendChild(a);
    a.click();
    a.remove();
    state.downloads.set(id, { id, url, filename: filename || "Preparing download", bytes: 0, length: null, speed: 0, status: "STARTING" });
    renderDownloads();
    $("downloadsPanel").classList.remove("hidden");
    toast("Download requested");
  }
  function fmt(n) {
    if (!Number.isFinite(n) || n < 0) return "0 B";
    if (n < 1000) return n + " B";
    if (n < 1000000) return (n / 1000).toFixed(1) + " KB";
    return (n / 1000000).toFixed(2) + " MB";
  }
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, c =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  function renderDownloads() {
    const list = $("downloadList");
    const all = Array.from(state.downloads.values()).sort((a,b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    $("activeCount").textContent = all.filter(x => x.status === "STARTING" || x.status === "STREAMING").length;
    if (!all.length) {
      list.innerHTML = '<p class="empty">Your downloads will appear here.</p>';
      return;
    }
    list.innerHTML = all.map(t => {
      const active = t.status === "STARTING" || t.status === "STREAMING";
      const pct = t.length > 0 ? Math.min(100, 100 * t.bytes / t.length) : (t.status === "COMPLETED" ? 100 : 0);
      return '<article class="transfer"><div class="transfer-top"><div><div class="transfer-name">' + esc(t.filename || "download") +
        '</div><div class="transfer-status">' + esc(t.status) + ' · ' + fmt(t.bytes) +
        (t.length != null ? ' / ' + fmt(t.length) : '') + ' · ' + fmt(t.speed || 0) + '/s</div></div>' +
        '<div class="transfer-actions">' + (active ? '<button data-cancel="' + esc(t.id) + '">Cancel</button>' : '') +
        ((t.status === "FAILED" || t.status === "LIMIT_EXCEEDED" || t.status === "CANCELLED") ? '<button data-dismiss="' + esc(t.id) + '">×</button>' : '') +
        '</div></div><div class="progress-track"><div class="progress-fill" style="width:' + pct + '%"></div></div>' +
        (t.error ? '<div class="transfer-error">' + esc(t.error) + '</div>' : '') + '</article>';
    }).join("");
  }
  async function status() {
    try {
      const res = await fetch("/api/status", { cache: "no-store" });
      if (!res.ok) return;
      const data = await res.json();
      (data.downloads || []).forEach(t => state.downloads.set(t.id, {
        id: t.id, url: t.url, filename: t.filename, bytes: t.bytes, length: t.length === null ? null : t.length,
        speed: t.speed || 0, status: t.status, error: t.error, updatedAt: Date.now()
      }));
      renderDownloads();
    } catch (_) {}
  }
  $("addressForm").addEventListener("submit", e => { e.preventDefault(); navigate(); });
  $("homeSearch").addEventListener("submit", e => { e.preventDefault(); navigate($("homeQuery").value); });
  $("backBtn").addEventListener("click", () => {
    if (state.index > 0) { state.index--; navigate(state.history[state.index], false); }
    else home();
  });
  $("forwardBtn").addEventListener("click", () => {
    if (state.index < state.history.length - 1) { state.index++; navigate(state.history[state.index], false); }
  });
  $("reloadBtn").addEventListener("click", () => { if (state.current) navigate(state.current, false); });
  $("homeBtn").addEventListener("click", home);
  $("downloadCurrentBtn").addEventListener("click", () => download(state.current, ""));
  $("downloadsToggle").addEventListener("click", () => $("downloadsPanel").classList.toggle("hidden"));
  $("closeDownloads").addEventListener("click", () => $("downloadsPanel").classList.add("hidden"));
  $("downloadList").addEventListener("click", async e => {
    const cancel = e.target.closest("[data-cancel]");
    const dismiss = e.target.closest("[data-dismiss]");
    if (cancel) {
      const id = cancel.getAttribute("data-cancel");
      try { await fetch("/api/cancel?id=" + encodeURIComponent(id), { cache: "no-store" }); } catch (_) {}
      toast("Cancellation requested");
    }
    if (dismiss) state.downloads.delete(dismiss.getAttribute("data-dismiss"));
    renderDownloads();
  });
  window.addEventListener("message", e => {
    if (e.source !== viewer.contentWindow || !e.data || e.data.type !== "lanrelay-download") return;
    download(e.data.url, e.data.filename || "");
  });
  viewer.addEventListener("load", () => loading(false));
  viewer.addEventListener("error", () => { loading(false); toast("Page failed to load"); });
  setInterval(status, 800);
  status();
  home();
})();
