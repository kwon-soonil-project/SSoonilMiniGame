import { describe, expect, it, vi } from 'vitest'
import { ReconnectionTimeMode } from '@stomp/stompjs'
import { RealtimeClient, stompClientConfig, type StompClientPort } from './realtimeClient'

describe('RealtimeClient', () => {
  it('uses bounded exponential reconnection backoff for the shared STOMP client', () => {
    const config = stompClientConfig()

    expect(config.reconnectTimeMode).toBe(ReconnectionTimeMode.EXPONENTIAL)
    expect(config.reconnectDelay).toBe(1_000)
    expect(config.maxReconnectDelay).toBe(15_000)
  })

  it('publishes room commands through the existing connected STOMP client', async () => {
    const publish = vi.fn()
    const client: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() { this.active = true; this.onConnect({}) },
      deactivate: vi.fn(async () => undefined),
      subscribe: () => ({ unsubscribe: vi.fn() }),
      publish,
    }
    const realtime = new RealtimeClient(() => client)
    await realtime.connect()

    realtime.publish('/app/rooms/room-1/commands', '{"type":"PLAYER_READY"}')

    expect(publish).toHaveBeenCalledWith({
      destination: '/app/rooms/room-1/commands',
      body: '{"type":"PLAYER_READY"}',
    })
  })

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

  it('reuses the active client while the broker reconnects after a socket close', async () => {
    let factoryCalls = 0
    let activations = 0
    const client: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        activations += 1
        this.active = true
        this.onConnect({})
      },
      deactivate: vi.fn(async () => undefined),
      subscribe: () => ({ unsubscribe: vi.fn() }),
    }
    const realtime = new RealtimeClient(() => {
      factoryCalls += 1
      return client
    })
    await realtime.connect()

    client.onWebSocketClose({})
    const reconnecting = realtime.connect()
    client.onConnect({})
    await reconnecting

    expect(factoryCalls).toBe(1)
    expect(activations).toBe(1)
    expect(realtime.connectionState.value).toBe('connected')
  })

  it('reuses an active client after a STOMP error instead of activating a duplicate', async () => {
    let factoryCalls = 0
    let activations = 0
    const client: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        activations += 1
        this.active = true
        if (activations === 1) this.onStompError({})
      },
      deactivate: vi.fn(async () => undefined),
      subscribe: () => ({ unsubscribe: vi.fn() }),
    }
    const realtime = new RealtimeClient(() => {
      factoryCalls += 1
      return client
    })
    await expect(realtime.connect()).rejects.toThrow('실시간 연결')

    const reconnecting = realtime.connect()
    client.onConnect({})
    await reconnecting

    expect(factoryCalls).toBe(1)
    expect(activations).toBe(1)
  })

  it('deactivates an inactive failed client before activating its replacement', async () => {
    const lifecycle: string[] = []
    const first: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        lifecycle.push('activate-first')
        this.active = false
        this.onStompError({})
      },
      deactivate: vi.fn(async () => { lifecycle.push('deactivate-first') }),
      subscribe: () => ({ unsubscribe: vi.fn() }),
    }
    const second: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        lifecycle.push('activate-second')
        this.active = true
        this.onConnect({})
      },
      deactivate: vi.fn(async () => undefined),
      subscribe: () => ({ unsubscribe: vi.fn() }),
    }
    const clients = [first, second]
    const realtime = new RealtimeClient(() => clients.shift()!)
    await expect(realtime.connect()).rejects.toThrow()
    await realtime.connect()

    expect(lifecycle).toEqual(['activate-first', 'deactivate-first', 'activate-second'])
  })

  it('ignores callbacks from a replaced client', async () => {
    const secondSubscriptions: string[] = []
    const first: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() { this.onStompError({}) },
      deactivate: vi.fn(async () => undefined),
      subscribe: () => ({ unsubscribe: vi.fn() }),
    }
    const second: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() { this.active = true },
      deactivate: vi.fn(async () => undefined),
      subscribe(destination) {
        secondSubscriptions.push(destination)
        return { unsubscribe: vi.fn() }
      },
    }
    const clients = [first, second]
    const realtime = new RealtimeClient(() => clients.shift()!)
    realtime.subscribeLobby(() => undefined)
    await expect(realtime.connect()).rejects.toThrow()
    const replacement = realtime.connect()
    await vi.waitFor(() => expect(second.active).toBe(true))

    first.onConnect({})
    expect(secondSubscriptions).toEqual([])
    second.onConnect({})
    await replacement
    expect(secondSubscriptions).toEqual(['/topic/lobby'])
  })

  it('cancels a pending replacement when disconnect waits for inactive deactivation', async () => {
    let releaseDeactivation!: () => void
    const deactivation = new Promise<void>((resolve) => { releaseDeactivation = resolve })
    let factoryCalls = 0
    let activations = 0
    const first: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        activations += 1
        this.onStompError({})
      },
      deactivate: vi.fn(() => deactivation),
      subscribe: () => ({ unsubscribe: vi.fn() }),
    }
    const second: StompClientPort = {
      active: false,
      onConnect: () => undefined,
      onStompError: () => undefined,
      onWebSocketClose: () => undefined,
      activate() {
        activations += 1
        this.active = true
        this.onConnect({})
      },
      deactivate: vi.fn(async () => undefined),
      subscribe: () => ({ unsubscribe: vi.fn() }),
    }
    const clients = [first, second]
    const realtime = new RealtimeClient(() => {
      factoryCalls += 1
      return clients.shift()!
    })
    await expect(realtime.connect()).rejects.toThrow('실시간 연결')

    const pendingReplacement = realtime.connect()
    await vi.waitFor(() => expect(first.deactivate).toHaveBeenCalled())
    const disconnecting = realtime.disconnect()
    let disconnectSettled = false
    void disconnecting.then(() => { disconnectSettled = true })
    await Promise.resolve()
    expect(disconnectSettled).toBe(false)
    releaseDeactivation()

    await disconnecting
    await expect(pendingReplacement).rejects.toThrow('종료')
    expect(realtime.connectionState.value).toBe('disconnected')
    expect(factoryCalls).toBe(1)
    expect(activations).toBe(1)

    await realtime.connect()
    expect(factoryCalls).toBe(2)
    expect(activations).toBe(2)
    expect(realtime.connectionState.value).toBe('connected')
  })
})
