// Service worker of Stundenplan MD Web: precaches the app shell, serves it offline (cache first), never talks to any
// other origin. VERSION and ASSETS are filled in at build time (vite.config.ts).
const VERSION = '__VERSION__'
const ASSETS = __ASSETS__
const CACHE = `stundenplan-md-${VERSION}`

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)))
})

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('stundenplan-md-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('message', (e) => {
  if (e.data === 'skipWaiting') self.skipWaiting()
})

self.addEventListener('fetch', (e) => {
  const req = e.request
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return
  if (req.mode === 'navigate') {
    e.respondWith(caches.match('./index.html', { ignoreVary: true }).then((r) => r || fetch(req)))
    return
  }
  // ignoreVary: the precache requests had no Origin header, module script requests do (Vary: Origin).
  e.respondWith(caches.match(req, { ignoreVary: true }).then((r) => r || fetch(req)))
})
