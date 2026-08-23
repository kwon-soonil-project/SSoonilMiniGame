import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { apiRequest } from '../../shared/api/apiClient'
import { realtimeClient, type RealtimeClient } from '../../shared/realtime/realtimeClient'

type LobbyRealtimePort = Pick<RealtimeClient, 'subscribeLobby' | 'connect'>

export type GameType = 'LIAR' | 'DRAWING' | 'CHOSUNG' | 'MAJORITY'

export interface LobbyRoom {
  roomId: string
  code: string
  title: string
  gameType: GameType
  status: 'WAITING' | 'PLAYING'
  passwordProtected: boolean
  participantCount: number
  maxParticipants: number
  hostNickname: string
}

export interface CreateRoomInput {
  title: string
  visibility: 'PUBLIC' | 'PRIVATE'
  password?: string
  gameType: GameType
}

export interface CreatedRoom {
  roomId: string
  code: string
  title: string
  visibility: 'PUBLIC' | 'PRIVATE'
  gameType: GameType
  status: 'WAITING' | 'PLAYING'
  passwordProtected: boolean
  participantCount: number
  maxParticipants: number
}

export interface LobbyEvent {
  version: number
  eventId: string
  requestId: string
  roomId: string
  actorId: string
  type: string
  sequence: number
  occurredAt: string
  payload: unknown
}

export const useLobbyStore = defineStore('lobby', () => {
  const rooms = ref<LobbyRoom[]>([])
  const filters = reactive<{ query: string; gameType: GameType | ''; available: boolean }>({
    query: '',
    gameType: '',
    available: true,
  })
  const loading = ref(false)
  const creating = ref(false)
  const error = ref<string | null>(null)
  const lastSequences = new Map<string, number>()
  let unsubscribeLobby: (() => void) | null = null

  async function loadRooms(): Promise<void> {
    loading.value = true
    error.value = null
    const parameters = new URLSearchParams()
    if (filters.query.trim()) parameters.set('query', filters.query.trim())
    if (filters.gameType) parameters.set('gameType', filters.gameType)
    parameters.set('available', String(filters.available))
    try {
      const loaded = await apiRequest<unknown[]>(`/api/v1/lobby/rooms?${parameters}`)
      rooms.value = loaded.flatMap(value => {
        const room = lobbyRoomFrom(value)
        return room ? [room] : []
      }).sort(compareRooms)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '방 목록을 불러오지 못했습니다.'
      throw cause
    } finally {
      loading.value = false
    }
  }

  async function createRoom(input: CreateRoomInput): Promise<CreatedRoom> {
    creating.value = true
    error.value = null
    const normalizedPassword = input.password?.trim()
    const body = {
      title: input.title.trim(),
      visibility: input.visibility,
      ...(normalizedPassword ? { password: normalizedPassword } : {}),
      gameType: input.gameType,
    }
    try {
      return await apiRequest<CreatedRoom>('/api/v1/rooms', {
        method: 'POST',
        body: JSON.stringify(body),
      })
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '방을 만들지 못했습니다.'
      throw cause
    } finally {
      creating.value = false
    }
  }

  function applyLobbyEvent(rawEvent: unknown): void {
    if (!isLobbyEvent(rawEvent)) return
    const previousSequence = lastSequences.get(rawEvent.roomId)
    if (previousSequence !== undefined && rawEvent.sequence <= previousSequence) return
    lastSequences.set(rawEvent.roomId, rawEvent.sequence)

    if (rawEvent.type === 'LOBBY_ROOM_REMOVE') {
      rooms.value = rooms.value.filter(room => room.roomId !== rawEvent.roomId)
      return
    }
    if (rawEvent.type !== 'LOBBY_ROOM_UPSERT') return
    const room = lobbyRoomFrom(rawEvent.payload)
    if (!room) return
    const roomIndex = rooms.value.findIndex(candidate => candidate.roomId === room.roomId)
    if (roomIndex === -1) rooms.value.push(room)
    else rooms.value.splice(roomIndex, 1, room)
    rooms.value.sort(compareRooms)
  }

  async function initialize(client: LobbyRealtimePort = realtimeClient): Promise<void> {
    if (unsubscribeLobby) return
    try {
      await loadRooms()
    } catch {
      // The view renders the store error and can retry without an unhandled promise.
    }
    unsubscribeLobby = client.subscribeLobby(applyLobbyEvent)
    try {
      await client.connect()
    } catch {
      error.value ??= '실시간 연결이 끊겼어요. 목록을 새로고침해 주세요.'
    }
  }

  function dispose(): void {
    unsubscribeLobby?.()
    unsubscribeLobby = null
  }

  return { rooms, filters, loading, creating, error, loadRooms, createRoom, applyLobbyEvent, initialize, dispose }
})

function compareRooms(left: LobbyRoom, right: LobbyRoom): number {
  if (left.status !== right.status) return left.status === 'WAITING' ? -1 : 1
  return left.title.localeCompare(right.title, 'ko')
}

function isLobbyEvent(value: unknown): value is LobbyEvent {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Partial<LobbyEvent>
  return typeof candidate.roomId === 'string'
    && typeof candidate.type === 'string'
    && typeof candidate.sequence === 'number'
    && 'payload' in candidate
}

function lobbyRoomFrom(value: unknown): LobbyRoom | null {
  if (typeof value !== 'object' || value === null) return null
  const room = value as Partial<LobbyRoom>
  if (
    typeof room.roomId !== 'string'
    || typeof room.code !== 'string'
    || typeof room.title !== 'string'
    || !isGameType(room.gameType)
    || (room.status !== 'WAITING' && room.status !== 'PLAYING')
    || typeof room.passwordProtected !== 'boolean'
    || typeof room.participantCount !== 'number'
    || typeof room.maxParticipants !== 'number'
    || typeof room.hostNickname !== 'string'
  ) return null
  return {
    roomId: room.roomId,
    code: room.code,
    title: room.title,
    gameType: room.gameType,
    status: room.status,
    passwordProtected: room.passwordProtected,
    participantCount: room.participantCount,
    maxParticipants: room.maxParticipants,
    hostNickname: room.hostNickname,
  }
}

function isGameType(value: unknown): value is GameType {
  return value === 'LIAR' || value === 'DRAWING' || value === 'CHOSUNG' || value === 'MAJORITY'
}
