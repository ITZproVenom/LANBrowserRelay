(() => {
  // The viewer is sandboxed without allow-same-origin, so its origin is opaque.
  // Only relay rewritten HTTP(S) anchors are forwarded to the outer browser UI.
  const unwrapRelayRoute = raw => {
    try {
      const target = new URL(raw, location.href);
      if (target.pathname === '/browse' && target.host === location.host) {
        const original = target.searchParams.get('url');
        return original ? new URL(original) : null;
      }
      return target;
    } catch (_) {
      return null;
    }
  };

  document.addEventListener('click', event => {
    const anchor = event.target instanceof Element
      ? event.target.closest('a[href]') : null;
    if (!anchor) return;
    const target = unwrapRelayRoute(anchor.href);
    if (!target || !['http:', 'https:'].includes(target.protocol)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    parent.postMessage({ type: 'lanbrowserrelay:navigate', url: target.href }, '*');
  }, true);
})();
