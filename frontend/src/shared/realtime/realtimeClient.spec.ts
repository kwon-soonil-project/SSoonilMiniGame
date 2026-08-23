import { describe, expect, it, vi } from 'vitest'
import { RealtimeClient, type StompClientPort } from './realtimeClient'

describe('RealtimeClient', () => {
  it('connects with cookies and installs the lobby subscription', async () => {
    const subscriptions: string[] = []
    const client: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        this.active = true
        this.onConnect({} as never)
      },
      deactivate: vi.fn(async () => undefined),
      subscribe(destination) {
        subscriptions.push(destination)
        return { unsubscribe: vi.fn() }
      },
    }
    const realtime = new RealtimeClient(() => client)
    realtime.subscribeLobby(() => undefined)

    await realtime.connect()

    expect(realtime.connectionState.value).toBe('connected')
    expect(subscriptions).toEqual(['/topic/lobby'])
  })

  it('keeps a generic destination subscription API for later room subscriptions', async () => {
    const subscriptions: string[] = []
    const client: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        this.active = true
        this.onConnect({} as never)
      },
      deactivate: vi.fn(async () => undefined),
      subscribe(destination) {
        subscriptions.push(destination)
        return { unsubscribe: vi.fn() }
      },
    }
    const realtime = new RealtimeClient(() => client)
    realtime.subscribe('/topic/rooms/room-1', () => undefined)

    await realtime.connect()

    expect(subscriptions).toEqual(['/topic/rooms/room-1'])
  })
})
