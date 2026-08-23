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
    const client = this.factory()
    this.client = client
    this.connecting = new Promise<void>((resolve, reject) => {
      client.onConnect = () => {
        this.state.value = 'connected'
        this.connecting = null
        for (const destination of this.handlers.keys()) this.installSubscription(destination)
        resolve()
      }
      client.onStompError = () => {
        this.state.value = 'failed'
        this.connecting = null
        reject(new Error('실시간 연결을 설정하지 못했습니다.'))
      }
      client.onWebSocketClose = () => {
        this.brokerSubscriptions.clear()
        this.state.value = client.active ? 'reconnecting' : 'disconnected'
      }
      client.activate()
    })
    return this.connecting
  }

  async disconnect(): Promise<void> {
    const client = this.client
    this.client = null
    this.connecting = null
    this.brokerSubscriptions.clear()
    if (client?.active) await client.deactivate()
    this.state.value = 'disconnected'
  }

  private installSubscription(destination: string): void {
    if (!this.client || this.brokerSubscriptions.has(destination)) return
    const subscription = this.client.subscribe(destination, (message: Pick<IMessage, 'body'>) => {
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
