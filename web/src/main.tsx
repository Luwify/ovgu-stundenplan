import { render } from 'preact'
import { App } from './App'
import { Model } from './model'
import { loadAll } from './store'
import './styles.css'

async function start() {
  const model = new Model(await loadAll())
  render(<App model={model} />, document.getElementById('app')!)
  registerServiceWorker(model)
}

/**
 * Offline via a service worker (dist/sw.js, see vite.config.ts). A new version waits until the user reloads from the
 * banner: "Neue Version verfügbar – Neu laden".
 */
function registerServiceWorker(model: Model) {
  if (!('serviceWorker' in navigator) || import.meta.env.DEV) return
  // Reload only for an update the user asked for: the first install also changes the controller (clients.claim).
  let requested = false
  let reloading = false
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (!requested || reloading) return
    reloading = true
    location.reload()
  })
  navigator.serviceWorker.register('./sw.js').then((reg) => {
    const offer = (w: ServiceWorker) => {
      model.updateReady = () => { requested = true; w.postMessage('skipWaiting') }
      model.emit()
    }
    if (reg.waiting && navigator.serviceWorker.controller) offer(reg.waiting)
    reg.addEventListener('updatefound', () => {
      const w = reg.installing
      w?.addEventListener('statechange', () => {
        if (w.state === 'installed' && navigator.serviceWorker.controller) offer(w)
      })
    })
    const check = () => void reg.update().catch(() => {})
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') check() })
    setInterval(check, 60 * 60 * 1000)
  }).catch(() => {})
}

void start()
