import { defineConfig, type Plugin } from 'vite'
import preact from '@preact/preset-vite'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = fileURLToPath(new URL('.', import.meta.url))

const version = process.env.APP_VERSION ?? `dev-${new Date().toISOString().slice(0, 10)}`

/** Writes dist/sw.js from src/sw.template.js with the list of built files and a version derived from their content. */
function serviceWorker(): Plugin {
  return {
    name: 'stundenplan-sw',
    apply: 'build',
    generateBundle(_, bundle) {
      const files = Object.keys(bundle).filter((f) => !f.endsWith('.map'))
      const statics = ['icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png', 'icons/favicon-32.png', 'manifest.webmanifest']
      const assets = ['./', './index.html', ...[...files.filter((f) => f !== 'index.html'), ...statics].map((f) => `./${f}`)]
      const hash = createHash('sha256')
      for (const f of files) {
        const o = bundle[f]
        hash.update(f).update(o.type === 'chunk' ? o.code : typeof o.source === 'string' ? o.source : Buffer.from(o.source))
      }
      const template = readFileSync(resolve(here, 'src/sw.template.js'), 'utf8')
      this.emitFile({
        type: 'asset',
        fileName: 'sw.js',
        source: template.replace("'__VERSION__'", JSON.stringify(`${version}-${hash.digest('hex').slice(0, 12)}`)).replace('__ASSETS__', JSON.stringify(assets)),
      })
    },
  }
}

export default defineConfig({
  base: './',
  plugins: [preact(), serviceWorker()],
  define: { __APP_VERSION__: JSON.stringify(version) },
  build: { target: 'safari15', sourcemap: false, chunkSizeWarningLimit: 900 },
  server: { host: '127.0.0.1' },
})
