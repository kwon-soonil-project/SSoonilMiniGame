import { defineStore } from 'pinia'
import { ref, type Ref } from 'vue'
import { ApiError } from '../../shared/api/ApiError'
import { apiRequest } from '../../shared/api/apiClient'
import { EventSequencer, type SequencedEvent } from '../../shared/realtime/eventSequencer'
import { realtimeClient, type ConnectionState } from '../../shared/realtime/realtimeClient'

export type GameType = 'LIAR' | 'DRAWING' | 'CHOSUNG' | 'MAJORITY'
export type RoomStatus = 'WAITING' | 'PLAYING' | 'CLOSED'
export type RoomConnection = 'connecting' | 'connected' | 'reconnecting' | 'failed'

export interface RoomParticipant {
  actorId: string
  nickname: string
  ready: boolean
  spectator: boolean
}

export interface RoomSettings {
  gameType: GameType
  maxParticipants: number
  rounds: number
  actionSeconds: number
  discussionSeconds: number
  categoryPack: string
}

export interface RoomSnapshot extends RoomSettings {
  roomId: string
  code: string
  title: string
  visibility: 'PUBLIC' | 'PRIVATE'
  status: RoomStatus
  passwordProtected: boolean
  participantCount: number
  hostId: string
  sequence: number
  participants: RoomParticipant[]
  chats: RoomChatMessage[]
}

export interface RoomChatMessage {
  messageId: string
  actorId: string
  nickname: string
  body: string
  sentAt: string
}

export interface RoomEvent extends SequencedEvent {
  version: number
  eventId: string
  requestId: string
  roomId: string
  actorId: string
  occurredAt: string
  payload: Record<string, unknown>
}

export interface RoomRealtimePort {
  connectionState: Readonly<Ref<ConnectionState>>
  subscribe(destination: string, handler: (payload: unknown) => void): () => void
  subscribeConnectionState(handler: (state: ConnectionState) => void): () => void
  connect(): Promise<void>
  publish(destination: string, body: string): void | Promise<void>
}

interface RoomCommand {
  requestId: string
  type: 'PLAYER_READY' | 'CHAT_SEND' | 'ANSWER_SUBMIT' | 'ROOM_SETTINGS_UPDATE'
  payload: Record<string, unknown>
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

function numberValue(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function booleanValue(value: unknown): boolean {
  return value === true
}

function sanitizeParticipant(value: unknown): RoomParticipant | null {
  if (!isRecord(value) || typeof value.actorId !== 'string' || typeof value.nickname !== 'string') return null
  return {
    actorId: value.actorId,
    nickname: value.nickname,
    ready: booleanValue(value.ready),
    spectator: booleanValue(value.spectator),
  }
}

function sanitizeChatMessage(value: unknown): RoomChatMessage | null {
  if (!isRecord(value) || typeof value.messageId !== 'string' || typeof value.actorId !== 'string'
    || typeof value.nickname !== 'string' || typeof value.body !== 'string'
    || typeof value.sentAt !== 'string') return null
  return {
    messageId: value.messageId,
    actorId: value.actorId,
    nickname: value.nickname,
    body: value.body,
    sentAt: value.sentAt,
  }
}

function recentUniqueChats(values: unknown[]): RoomChatMessage[] {
  const unique = new Map<string, RoomChatMessage>()
  values.forEach(value => {
    const message = sanitizeChatMessage(value)
    if (message) unique.set(message.messageId, message)
  })
  return [...unique.values()].slice(-100)
}

function sanitizeSnapshot(value: unknown): RoomSnapshot {
  if (!isRecord(value) || typeof value.roomId !== 'string' || typeof value.code !== 'string') {
    throw new Error('방 상태 응답이 올바르지 않습니다.')
  }
  const participants = Array.isArray(value.participants)
    ? value.participants.map(sanitizeParticipant).filter((item): item is RoomParticipant => item !== null)
    : []
  return {
    roomId: value.roomId,
    code: value.code,
    title: stringValue(value.title),
    visibility: value.visibility === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC',
    gameType: value.gameType as GameType,
    status: value.status as RoomStatus,
    passwordProtected: booleanValue(value.passwordProtected),
    participantCount: numberValue(value.participantCount, participants.filter(item => !item.spectator).length),
    maxParticipants: numberValue(value.maxParticipants),
    hostId: stringValue(value.hostId),
    sequence: numberValue(value.sequence),
    rounds: numberValue(value.rounds),
    actionSeconds: numberValue(value.actionSeconds),
    discussionSeconds: numberValue(value.discussionSeconds),
    categoryPack: stringValue(value.categoryPack),
    participants,
    chats: Array.isArray(value.chats) ? recentUniqueChats(value.chats) : [],
  }
}

function isRoomEvent(value: unknown): value is RoomEvent {
  return isRecord(value)
    && typeof value.sequence === 'number'
    && typeof value.type === 'string'
    && typeof value.roomId === 'string'
    && isRecord(value.payload)
}

const commandMessages: Record<string, string> = {
  CHAT_RATE_LIMITED: '채팅을 너무 빠르게 보내고 있어요. 잠시 후 다시 시도해 주세요.',
  CHAT_URL_NOT_ALLOWED: '채팅에는 외부 링크를 보낼 수 없어요.',
  ROOM_HOST_REQUIRED: '방장만 변경할 수 있어요.',
  ROOM_COMMAND_INVALID: '요청 내용을 확인해 주세요.',
}

const STALE_JOIN = Symbol('STALE_JOIN')
const allowableMissingMembershipCodes = new Set(['ROOM_NOT_FOUND', 'ROOM_PARTICIPANT_NOT_FOUND'])

export const useRoomStore = defineStore('room', () => {
  const snapshot = ref<RoomSnapshot | null>(null)
  const connection = ref<RoomConnection>('connecting')
  const loading = ref(false)
  const error = ref<string | null>(null)
  const commandError = ref<string | null>(null)
  const passwordRequired = ref(false)
  const chats = ref<RoomChatMessage[]>([])
  const unreadChatCount = ref(0)
  const chatOpen = ref(false)

  let realtime: RoomRealtimePort = realtimeClient
  let sequencer: EventSequencer<RoomSnapshot> | null = null
  let synchronizing = false
  let joined = false
  let connectedOnce = false
  let recovery: { generation: number; roomId: string; controller: AbortController; promise: Promise<RoomSnapshot> } | null = null
  let initialSnapshotRequest: AbortController | null = null
  let joinGeneration = 0
  let membershipRoomId: string | null = null
  let transitionTail: Promise<void> = Promise.resolve()
  let pendingCleanup: { roomId: string; cause: Error; blockedThroughGeneration: number } | null = null
  let bufferedPublicEvents: RoomEvent[] = []
  let bufferedPrivateEvents: RoomEvent[] = []
  let unsubscribePublic: (() => void) | null = null
  let unsubscribePrivate: (() => void) | null = null
  let unsubscribeState: (() => void) | null = null

  function join(code: string, password = '', port: RoomRealtimePort = realtimeClient): Promise<void> {
    const generation = ++joinGeneration
    initialSnapshotRequest?.abort()
    recovery?.controller.abort()
    recovery = null
    loading.value = true
    const operation = transitionTail.then(() => performJoin(code, password, port, generation))
    transitionTail = operation.catch(() => undefined)
    return operation
  }

  async function performJoin(
    code: string,
    password: string,
    port: RoomRealtimePort,
    generation: number,
  ): Promise<void> {
    let joinedRoomId: string | null = null
    try {
      if (pendingCleanup) {
        if (generation <= pendingCleanup.blockedThroughGeneration) {
          exposeCleanupFailure(pendingCleanup.cause)
          throw pendingCleanup.cause
        }
        await cleanupMembership(pendingCleanup.roomId)
        resetLocalRoomState()
      }
      if (generation !== joinGeneration) return

      const currentRoomId = membershipRoomId ?? snapshot.value?.roomId ?? null
      if (currentRoomId && snapshot.value?.code === code && joined) {
        loading.value = false
        return
      }
      if (currentRoomId) {
        await cleanupMembership(currentRoomId)
        resetLocalRoomState()
      }
      if (generation !== joinGeneration) return

      resetLocalRoomState()
      realtime = port
      connection.value = 'connecting'
      error.value = null
      commandError.value = null
      passwordRequired.value = false
      connectedOnce = false

      // A join is an unsafe mutation. Never abort it: a delayed success must be
      // observed so its server-side membership can be explicitly cleaned up.
      const joinedSnapshot = sanitizeSnapshot(await apiRequest<unknown>(`/api/v1/rooms/${code}/join`, {
        method: 'POST', body: JSON.stringify({ password }),
      }))
      joinedRoomId = joinedSnapshot.roomId
      membershipRoomId = joinedRoomId
      if (generation !== joinGeneration) throw STALE_JOIN

      const roomId = joinedRoomId
      synchronizing = true
      bufferedPublicEvents = []
      bufferedPrivateEvents = []
      unsubscribePublic = realtime.subscribe(`/topic/rooms/${roomId}`, payload => applyPublicEvent(payload, generation))
      unsubscribePrivate = realtime.subscribe(`/user/queue/rooms/${roomId}`, payload => applyPrivateEvent(payload, generation))
      unsubscribeState = realtime.subscribeConnectionState(state => handleConnectionState(state, generation))
      await realtime.connect()
      if (generation !== joinGeneration) throw STALE_JOIN
      const controller = new AbortController()
      initialSnapshotRequest = controller
      const authoritative = await fetchSnapshot(roomId, controller.signal)
      if (initialSnapshotRequest === controller) initialSnapshotRequest = null
      if (generation !== joinGeneration) throw STALE_JOIN
      replaceSnapshot(authoritative)
      sequencer = new EventSequencer(authoritative.sequence, () => reloadSnapshot(roomId, generation))
      synchronizing = false
      for (const event of bufferedPublicEvents.splice(0)) await routeEvent(event, generation)
      for (const event of bufferedPrivateEvents.splice(0)) await applyPrivateEvent(event, generation)
      if (generation !== joinGeneration) return
      joined = true
      connectedOnce = true
      connection.value = 'connected'
    } catch (cause) {
      if (cause === STALE_JOIN || generation !== joinGeneration) {
        disposeSubscriptions()
        resetLocalRoomState()
        if (joinedRoomId) await cleanupMembership(joinedRoomId)
        return
      }
      if (pendingCleanup?.cause === cause) throw cause
      synchronizing = false
      connection.value = 'failed'
      passwordRequired.value = cause instanceof ApiError && cause.code === 'ROOM_PASSWORD_INVALID'
      error.value = passwordRequired.value
        ? '이 방에 입장하려면 비밀번호가 필요해요.'
        : cause instanceof Error ? cause.message : '방에 입장하지 못했습니다.'
      disposeSubscriptions()
      throw cause
    } finally {
      if (generation === joinGeneration) {
        loading.value = false
      }
    }
  }

  async function fetchSnapshot(roomId: string, signal?: AbortSignal): Promise<RoomSnapshot> {
    return sanitizeSnapshot(await apiRequest<unknown>(`/api/v1/rooms/${roomId}/snapshot`, { signal }))
  }

  function reloadSnapshot(roomId: string, generation: number = joinGeneration): Promise<RoomSnapshot> {
    if (recovery?.roomId === roomId && recovery.generation === generation) return recovery.promise
    recovery?.controller.abort()
    const controller = new AbortController()
    const pending = fetchSnapshot(roomId, controller.signal).then(recovered => {
      if (generation === joinGeneration) replaceSnapshot(recovered)
      return recovered
    })
    const entry = { generation, roomId, controller, promise: pending }
    recovery = entry
    void pending.finally(() => { if (recovery === entry) recovery = null }).catch(() => undefined)
    return pending
  }

  function replaceSnapshot(next: RoomSnapshot): void {
    const authoritative = sanitizeSnapshot(next)
    snapshot.value = authoritative
    chats.value = [...authoritative.chats]
  }

  async function applyPublicEvent(payload: unknown, generation: number = joinGeneration): Promise<void> {
    if (generation !== joinGeneration) return
    if (!isRoomEvent(payload) || (snapshot.value && payload.roomId !== snapshot.value.roomId)) return
    if (synchronizing || !sequencer) {
      bufferedPublicEvents.push(payload)
      return
    }
    try {
      await routeEvent(payload, generation)
    } catch (cause) {
      handleRecoveryFailure(cause, generation)
    }
  }

  async function routeEvent(event: RoomEvent, generation: number = joinGeneration): Promise<void> {
    if (generation !== joinGeneration) return
    await sequencer?.accept(event, accepted => mutateFromEvent(accepted as RoomEvent))
    if (generation === joinGeneration && snapshot.value && sequencer) snapshot.value.sequence = sequencer.current
  }

  async function applyPrivateEvent(payload: unknown, generation: number = joinGeneration): Promise<void> {
    if (generation !== joinGeneration) return
    if (!isRoomEvent(payload) || (snapshot.value && payload.roomId !== snapshot.value.roomId)) return
    if (synchronizing) {
      bufferedPrivateEvents.push(payload)
      return
    }
    if (payload.sequence > (sequencer?.current ?? 0) + 1 && snapshot.value) {
      try {
        const recovered = await reloadSnapshot(snapshot.value.roomId, generation)
        if (generation !== joinGeneration) return
        sequencer?.reset(recovered.sequence)
      } catch (cause) {
        handleRecoveryFailure(cause, generation)
        return
      }
    }
    if (payload.type === 'COMMAND_REJECTED') {
      const code = stringValue(payload.payload.code, 'ROOM_COMMAND_INVALID')
      commandError.value = commandMessages[code] ?? '요청을 처리하지 못했습니다.'
    }
  }

  function mutateFromEvent(event: RoomEvent): void {
    const room = snapshot.value
    if (!room) return
    const payload = event.payload
    switch (event.type) {
      case 'PLAYER_JOINED': {
        const participant = sanitizeParticipant(payload)
        if (participant && !room.participants.some(item => item.actorId === participant.actorId)) {
          room.participants.push(participant)
        }
        break
      }
      case 'PLAYER_READY_CHANGED': {
        const participant = room.participants.find(item => item.actorId === payload.actorId)
        if (participant) participant.ready = booleanValue(payload.ready)
        break
      }
      case 'ROOM_SETTINGS_UPDATED':
        room.gameType = payload.gameType as GameType
        room.maxParticipants = numberValue(payload.maxParticipants, room.maxParticipants)
        room.rounds = numberValue(payload.rounds, room.rounds)
        room.actionSeconds = numberValue(payload.actionSeconds, room.actionSeconds)
        room.discussionSeconds = numberValue(payload.discussionSeconds, room.discussionSeconds)
        room.categoryPack = stringValue(payload.categoryPack, room.categoryPack)
        room.participants.forEach(participant => { participant.ready = false })
        break
      case 'HOST_TRANSFERRED':
        room.hostId = stringValue(payload.newHostId, room.hostId)
        break
      case 'PLAYER_LEFT':
        room.participants = room.participants.filter(item => item.actorId !== payload.actorId)
        break
      case 'ROOM_CLOSED':
        room.status = 'CLOSED'
        error.value = '방이 종료되었습니다.'
        break
      case 'CHAT_MESSAGE': {
        const message = sanitizeChatMessage(payload)
        if (message && !chats.value.some(item => item.messageId === message.messageId)) {
          chats.value.push(message)
          if (chats.value.length > 100) chats.value.shift()
          room.chats = [...chats.value]
          if (!chatOpen.value) unreadChatCount.value += 1
        }
        break
      }
    }
    room.participantCount = room.participants.filter(participant => !participant.spectator).length
  }

  function handleConnectionState(state: ConnectionState, generation: number = joinGeneration): void {
    if (generation !== joinGeneration) return
    if (state === 'connecting') connection.value = 'connecting'
    if (state === 'reconnecting' || state === 'disconnected') connection.value = 'reconnecting'
    if (state === 'failed') connection.value = 'failed'
    if (state !== 'connected') return
    if (connectedOnce && joined && snapshot.value) {
      void synchronizeCurrentRoom(generation)
    } else {
      connection.value = 'connected'
    }
    connectedOnce = true
  }

  async function synchronizeCurrentRoom(generation: number = joinGeneration): Promise<void> {
    const roomId = snapshot.value?.roomId
    if (!roomId || generation !== joinGeneration) return
    connection.value = 'reconnecting'
    synchronizing = true
    bufferedPublicEvents = []
    bufferedPrivateEvents = []
    try {
      const recovered = await reloadSnapshot(roomId, generation)
      if (generation !== joinGeneration) return
      sequencer?.reset(recovered.sequence)
      synchronizing = false
      for (const event of bufferedPublicEvents.splice(0)) await routeEvent(event, generation)
      for (const event of bufferedPrivateEvents.splice(0)) await applyPrivateEvent(event, generation)
      if (generation !== joinGeneration) return
      error.value = null
      connection.value = 'connected'
    } catch (cause) {
      handleRecoveryFailure(cause, generation)
    }
  }

  function handleRecoveryFailure(cause: unknown, generation: number): void {
    if (generation !== joinGeneration) return
    synchronizing = false
    bufferedPublicEvents = []
    bufferedPrivateEvents = []
    connection.value = 'failed'
    error.value = cause instanceof Error ? cause.message : '방 상태를 복구하지 못했습니다.'
  }

  function publish(type: RoomCommand['type'], payload: Record<string, unknown>): void {
    if (!snapshot.value || connection.value !== 'connected') {
      commandError.value = '실시간 연결을 복구한 뒤 다시 시도해 주세요.'
      return
    }
    commandError.value = null
    const command: RoomCommand = { requestId: crypto.randomUUID(), type, payload }
    try {
      const sent = realtime.publish(`/app/rooms/${snapshot.value.roomId}/commands`, JSON.stringify(command))
      if (sent instanceof Promise) {
        void sent.catch(() => { commandError.value = '실시간 요청을 보내지 못했습니다. 다시 시도해 주세요.' })
      }
    } catch {
      commandError.value = '실시간 요청을 보내지 못했습니다. 다시 시도해 주세요.'
    }
  }

  function sendReady(ready: boolean): void { publish('PLAYER_READY', { ready }) }
  function sendChat(body: string): void { publish('CHAT_SEND', { body }) }
  function sendAnswer(body: string): void { publish('ANSWER_SUBMIT', { body }) }
  function updateSettings(settings: RoomSettings): void { publish('ROOM_SETTINGS_UPDATE', { ...settings }) }

  async function leave(): Promise<void> {
    const roomId = membershipRoomId ?? snapshot.value?.roomId
    if (!roomId) return
    await cleanupMembership(roomId)
    clearRoom()
  }

  async function cleanupMembership(roomId: string): Promise<void> {
    try {
      await apiRequest<void>(`/api/v1/rooms/${roomId}/leave`, {
        method: 'POST', headers: { 'X-Request-Id': crypto.randomUUID() },
      })
    } catch (cause) {
      if (!(cause instanceof ApiError && allowableMissingMembershipCodes.has(cause.code))) {
        const stableCause = cause instanceof Error ? cause : new Error('이전 방을 정리하지 못했습니다.')
        pendingCleanup = {
          roomId,
          cause: stableCause,
          blockedThroughGeneration: joinGeneration,
        }
        exposeCleanupFailure(stableCause)
        throw stableCause
      }
    }
    if (membershipRoomId === roomId) membershipRoomId = null
    if (pendingCleanup?.roomId === roomId) pendingCleanup = null
  }

  function exposeCleanupFailure(cause: Error): void {
    connection.value = 'failed'
    error.value = cause.message || '이전 방을 정리하지 못했습니다.'
  }

  function openChat(): void {
    chatOpen.value = true
    unreadChatCount.value = 0
  }
  function closeChat(): void { chatOpen.value = false }

  function disposeSubscriptions(): void {
    unsubscribePublic?.()
    unsubscribePrivate?.()
    unsubscribeState?.()
    unsubscribePublic = null
    unsubscribePrivate = null
    unsubscribeState = null
  }

  function resetLocalRoomState(): void {
    initialSnapshotRequest?.abort()
    initialSnapshotRequest = null
    recovery?.controller.abort()
    recovery = null
    disposeSubscriptions()
    snapshot.value = null
    sequencer = null
    joined = false
    chats.value = []
    unreadChatCount.value = 0
    synchronizing = false
    bufferedPublicEvents = []
    bufferedPrivateEvents = []
  }

  function clearRoom(): void {
    joinGeneration += 1
    resetLocalRoomState()
  }

  return {
    snapshot, connection, loading, error, commandError, passwordRequired, chats, unreadChatCount,
    chatOpen, join, applyPublicEvent, applyPrivateEvent, sendReady, sendChat, sendAnswer,
    updateSettings, leave, openChat, closeChat, clearRoom, retryRecovery: synchronizeCurrentRoom,
  }
})
