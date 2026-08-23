import { Client, type IMessage } from '@stomp/stompjs'
import { readonly, ref, type DeepReadonly, type Ref } from 'vue'

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'failed'
export type RealtimeMessageHandler = (payload: unknown) => void

interface SubscriptionPort {
  unsubscribe(): void
}

export interface StompClientPort {
  active: boolean
  onConnect: (frame: unknown) => void
  onStompError: (frame: unknown) => void
  onWebSocketClose: (event: unknown) => void
  activate(): void
  deactivate(): Promise<unknown>
  subscribe(destination: string, callback: (message: { body: string }) => void): SubscriptionPort
}

type StompFactory = () => StompClientPort

function websocketUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

function defaultFactory(): StompClientPort {
  return new Client({
    brokerURL: websocketUrl(),
    reconnectDelay: 2_000,
    connectionTimeout: 8_000,
  }) as unknown as StompClientPort
}

export class RealtimeClient {
  private client: StompClientPort | null = null
  private readonly handlers = new Map<string, Set<RealtimeMessageHandler>>()
  private readonly brokerSubscriptions = new Map<string, SubscriptionPort>()
  private connecting: Promise<void> | null = null
  private readonly waiters: Array<{
    client: StompClientPort
    resolve: () => void
    reject: (cause: Error) => void
  }> = []
  private readonly state = ref<ConnectionState>('disconnected')
  readonly connectionState: DeepReadonly<Ref<ConnectionState>> = readonly(this.state)

  constructor(private readonly factory: StompFactory = defaultFactory) {}

  subscribeLobby(handler: RealtimeMessageHandler): () => void {
    return this.subscribe('/topic/lobby', handler)
  }

  subscribe(destination: string, handler: RealtimeMessageHandler): () => void {
    const destinationHandlers = this.handlers.get(destination) ?? new Set<RealtimeMessageHandler>()
    destinationHandlers.add(handler)
    this.handlers.set(destination, destinationHandlers)
    if (this.state.value === 'connected') this.installSubscription(destination)

    return () => {
      const current = this.handlers.get(destination)
      current?.delete(handler)
      if (current?.size === 0) {
        this.handlers.delete(destination)
        this.brokerSubscriptions.get(destination)?.unsubscribe()
        this.brokerSubscriptions.delete(destination)
      }
    }
  }

  connect(): Promise<void> {
    if (this.state.value === 'connected') return Promise.resolve()
    if (this.connecting) return this.connecting
    this.state.value = 'connecting'
    const attempt = this.connectCurrentOrReplacement()
    this.connecting = attempt
    void attempt.then(
      () => { if (this.connecting === attempt) this.connecting = null },
      () => { if (this.connecting === attempt) this.connecting = null },
    )
    return attempt
  }

  async disconnect(): Promise<void> {
    const client = this.client
    this.client = null
    this.connecting = null
    this.rejectWaiters(client, new Error('실시간 연결을 종료했습니다.'))
    this.brokerSubscriptions.clear()
    if (client?.active) await client.deactivate()
    this.state.value = 'disconnected'
  }

  private async connectCurrentOrReplacement(): Promise<void> {
    let client = this.client
    if (client?.active) {
      this.state.value = 'reconnecting'
      return this.waitForConnection(client)
    }
    if (client) {
      await client.deactivate()
      if (this.client !== client) return this.connectCurrentOrReplacement()
      this.client = null
      this.brokerSubscriptions.clear()
    }
    client = this.factory()
    this.client = client
    this.configureCallbacks(client)
    const connected = this.waitForConnection(client)
    client.activate()
    return connected
  }

  private configureCallbacks(client: StompClientPort): void {
    client.onConnect = () => {
      if (this.client !== client) return
      this.state.value = 'connected'
      for (const destination of this.handlers.keys()) this.installSubscription(destination, client)
      this.resolveWaiters(client)
    }
    client.onStompError = () => {
      if (this.client !== client) return
      this.state.value = 'failed'
      this.rejectWaiters(client, new Error('실시간 연결을 설정하지 못했습니다.'))
    }
    client.onWebSocketClose = () => {
      if (this.client !== client) return
      this.brokerSubscriptions.clear()
      this.state.value = client.active ? 'reconnecting' : 'disconnected'
    }
  }

  private waitForConnection(client: StompClientPort): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.waiters.push({ client, resolve, reject })
    })
  }

  private resolveWaiters(client: StompClientPort): void {
    for (let index = this.waiters.length - 1; index >= 0; index -= 1) {
      const waiter = this.waiters[index]!
      if (waiter.client !== client) continue
      this.waiters.splice(index, 1)
      waiter.resolve()
    }
  }

  private rejectWaiters(client: StompClientPort | null, cause: Error): void {
    if (!client) return
    for (let index = this.waiters.length - 1; index >= 0; index -= 1) {
      const waiter = this.waiters[index]!
      if (waiter.client !== client) continue
      this.waiters.splice(index, 1)
      waiter.reject(cause)
    }
  }

  private installSubscription(destination: string, client: StompClientPort = this.client!): void {
    if (this.client !== client || this.brokerSubscriptions.has(destination)) return
    const subscription = client.subscribe(destination, (message: Pick<IMessage, 'body'>) => {
      let payload: unknown
      try {
        payload = JSON.parse(message.body) as unknown
      } catch {
        return
      }
      for (const handler of this.handlers.get(destination) ?? []) handler(payload)
    })
    this.brokerSubscriptions.set(destination, subscription)
  }
}

export const realtimeClient = new RealtimeClient()
