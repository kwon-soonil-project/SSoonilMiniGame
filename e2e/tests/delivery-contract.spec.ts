import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { expect, test } from '@playwright/test'

test('compose runs the requested verified image while retaining a local build contract', () => {
  const repositoryRoot = path.resolve(import.meta.dirname, '../..')
  const output = execFileSync('docker', ['compose', 'config', '--format', 'json'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: { ...process.env, APP_IMAGE: 'minigame:verified-test-sha' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const compose = JSON.parse(output) as {
    services: { app: { image?: string; build?: { context?: string } } }
  }

  expect(compose.services.app.image).toBe('minigame:verified-test-sha')
  expect(compose.services.app.build?.context).toBe(repositoryRoot)
})
