import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'

export const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => setActivePinia(createPinia()))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
