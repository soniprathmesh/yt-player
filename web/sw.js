const CACHE = 'yt-v3-cache-v2';
const BASE = '/re/yt-v3/';
const SHELL = [BASE, BASE + 'manifest.json', BASE + 'icon-192.png', BASE + 'icon-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);

  // Only handle our own GET requests; never touch YouTube
  if (req.method !== 'GET' || url.origin !== location.origin || !url.pathname.startsWith(BASE)) return;

  // Page loads (including share-target ?url=...&text=...): network first, fallback to cached shell
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req, { cache: 'no-cache' })
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(BASE, copy));
          return res;
        })
        .catch(() => caches.match(BASE))
    );
    return;
  }

  // Static assets: cache first
  e.respondWith(caches.match(req).then((hit) => hit || fetch(req)));
});
