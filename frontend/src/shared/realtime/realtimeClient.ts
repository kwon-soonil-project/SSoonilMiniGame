import { Client, ReconnectionTimeMode, type IMessage, type StompConfig } from '@stomp/stompjs'
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
  publish?(frame: { destination: string; body: string }): void
}

type StompFactory = () => StompClientPort

function websocketUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

export function stompClientConfig(): StompConfig {
  return {
    brokerURL: websocketUrl(),
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    reconnectDelay: 1_000,
    maxReconnectDelay: 15_000,
    connectionTimeout: 8_000,
  }
}

function defaultFactory(): StompClientPort {
  return new Client(stompClientConfig()) as unknown as StompClientPort
}

export class RealtimeClient {
  private client: StompClientPort | null = null
  private readonly handlers = new Map<string, Set<RealtimeMessageHandler>>()
  private readonly brokerSubscriptions = new Map<string, SubscriptionPort>()
  private readonly deactivations = new Map<StompClientPort, Promise<unknown>>()
  private connecting: Promise<void> | null = null
  private generation = 0
  private readonly waiters: Array<{
    client: StompClientPort
    resolve: () => void
    reject: (cause: Error) => void
  }> = []
  private readonly state = ref<ConnectionState>('disconnected')
  private readonly stateHandlers = new Set<(state: ConnectionState) => void>()
  readonly connectionState: DeepReadonly<Ref<ConnectionState>> = readonly(this.state)

  constructor(private readonly factory: StompFactory = defaultFactory) {}

  subscribeLobby(handler: RealtimeMessageHandler): () => void {
    return this.subscribe('/topic/lobby', handler)
  }

  subscribeConnectionState(handler: (state: ConnectionState) => void): () => void {
    this.stateHandlers.add(handler)
    return () => this.stateHandlers.delete(handler)
  }

  publish(destination: string, body: string): void {
    if (this.state.value !== 'connected' || !this.client?.publish) {
      throw new Error('실시간 연결이 준비되지 않았습니다.')
    }
    this.client.publish({ destination, body })
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
    const generation = ++this.generation
    this.setState('connecting')
    const attempt = this.connectCurrentOrReplacement(generation)
    this.connecting = attempt
    void attempt.then(
      () => { if (this.connecting === attempt) this.connecting = null },
      () => { if (this.connecting === attempt) this.connecting = null },
    )
    return attempt
  }

  async disconnect(): Promise<void> {
    const generation = ++this.generation
    const client = this.client
    this.client = null
    this.connecting = null
    this.rejectAllWaiters(new Error('실시간 연결을 종료했습니다.'))
    this.brokerSubscriptions.clear()
    try {
      if (client) await this.deactivateClient(client)
    } finally {
      if (this.generation === generation) this.setState('disconnected')
    }
  }

  private async connectCurrentOrReplacement(generation: number): Promise<void> {
    this.assertCurrentGeneration(generation)
    let client = this.client
    if (client?.active) {
      this.setState('reconnecting')
      return this.waitForConnection(client)
    }
    if (client) {
      await this.deactivateClient(client)
      this.assertCurrentGeneration(generation)
      if (this.client !== client) return this.connectCurrentOrReplacement(generation)
      this.client = null
      this.brokerSubscriptions.clear()
    }
    this.assertCurrentGeneration(generation)
    client = this.factory()
    this.client = client
    this.configureCallbacks(client)
    const connected = this.waitForConnection(client)
    client.activate()
    return connected
  }

  private assertCurrentGeneration(generation: number): void {
    if (this.generation !== generation) throw new Error('실시간 연결을 종료했습니다.')
  }

  private deactivateClient(client: StompClientPort): Promise<unknown> {
    const pending = this.deactivations.get(client)
    if (pending) return pending
    const deactivation = Promise.resolve().then(() => client.deactivate()).then(
      result => {
        if (this.deactivations.get(client) === deactivation) this.deactivations.delete(client)
        return result
      },
      cause => {
        if (this.deactivations.get(client) === deactivation) this.deactivations.delete(client)
        throw cause
      },
    )
    this.deactivations.set(client, deactivation)
    return deactivation
  }

  private configureCallbacks(client: StompClientPort): void {
    client.onConnect = () => {
      if (this.client !== client) return
      for (const destination of this.handlers.keys()) this.installSubscription(destination, client)
      this.setState('connected')
      this.resolveWaiters(client)
    }
    client.onStompError = () => {
      if (this.client !== client) return
      this.setState('failed')
      this.rejectWaiters(client, new Error('실시간 연결을 설정하지 못했습니다.'))
    }
    client.onWebSocketClose = () => {
      if (this.client !== client) return
      this.brokerSubscriptions.clear()
      this.setState(client.active ? 'reconnecting' : 'disconnected')
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

  private rejectAllWaiters(cause: Error): void {
    for (const waiter of this.waiters.splice(0)) waiter.reject(cause)
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

  private setState(state: ConnectionState): void {
    if (this.state.value === state) return
    this.state.value = state
    for (const handler of this.stateHandlers) handler(state)
  }
}

export const realtimeClient = new RealtimeClient()
