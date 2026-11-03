// The Kotlin/JS build of :core (WebCore, share codec, frames, backup) is synced into src/generated/core by Gradle:
//   ./gradlew :core:webCore
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
if (!existsSync(join(here, '../src/generated/core/stundenplan-core.mjs'))) {
  console.error('Missing src/generated/core: run ./gradlew :core:webCore in the repository root first.')
  process.exit(1)
}
