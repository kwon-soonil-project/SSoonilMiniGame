import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../auth/authStore'
import type { GamePrivateState, LiarPhase } from '../games/gameTypes'
import RoomView from './RoomView.vue'
import { useRoomStore, type RoomSnapshot } from './roomStore'

const room: RoomSnapshot = {
  roomId: '00000000-0000-0000-0000-000000000701', code: '123456', title: '친구들과 한 판',
  visibility: 'PUBLIC', gameType: 'LIAR', status: 'WAITING', passwordProtected: false,
  participantCount: 2, maxParticipants: 10, hostId: 'host-1', sequence: 1,
  rounds: 3, actionSeconds: 30, discussionSeconds: 90, categoryPack: 'all',
  canStart: false,
  participants: [
    { actorId: 'host-1', nickname: '방장감자', ready: false, spectator: false },
    { actorId: 'guest-1', nickname: '참가감자', ready: false, spectator: false },
  ],
  chats: [],
  game: null,
}

function liarGame(
  phase: LiarPhase,
  privateState: GamePrivateState | null = { role: 'LIAR', category: '음식', word: '붕어빵', hintSubmitted: false, voteSubmitted: false },
  submittedPlayerIds: string[] = [],
) {
  return {
    publicState: {
      gameType: 'LIAR' as const, round: 1, phase, deadlineAt: '2026-08-27T00:00:05Z',
      ...(phase === 'HINTING' ? { currentHinter: 'host-1' } : {}),
      hints: [], submittedPlayerIds, scores: { 'host-1': 0, 'guest-1': 0 },
      ...(phase === 'REVOTING' ? { revoteCandidates: ['host-1', 'guest-1'] } : {}),
    },
    privateState,
  }
}

function mountRoom(
  actorId = 'host-1',
  attach = false,
  overrides: Partial<RoomSnapshot> = {},
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.actor = { actorId, actorType: 'GUEST', nickname: actorId === 'host-1' ? '방장감자' : '참가감자', memberId: null }
  const store = useRoomStore()
  store.snapshot = { ...structuredClone(room), ...overrides }
  store.connection = 'connected'
  store.join = vi.fn(async () => undefined)
  store.sendChat = vi.fn()
  store.sendReady = vi.fn()
  store.updateSettings = vi.fn()
  return { wrapper: mount(RoomView, { props: { code: '123456' }, ...(attach ? { attachTo: document.body } : {}), global: { plugins: [pinia] } }), store }
}

describe('RoomView', () => {
  it('renders participant, settings, and desktop chat regions with host-only controls', () => {
    const { wrapper } = mountRoom()

    expect(wrapper.get('[data-region="participants"]').text()).toContain('참가감자')
    expect(wrapper.get('[data-region="settings"]').find('select').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-region="chat"]').get('label').text()).toContain('메시지 입력')
    expect(wrapper.get('[data-action="start-game"]').text()).toContain('게임 시작')
    expect(wrapper.find('[data-action="ready"]').exists()).toBe(false)
  })

  it('shows an enabled game start button to the host only when canStart', () => {
    const { wrapper } = mountRoom('host-1', false, { canStart: true, status: 'WAITING' })

    expect(wrapper.get('[data-action="start-game"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-action="ready"]').exists()).toBe(false)
  })

  it('keeps the ready flow for a non-host participant', () => {
    const { wrapper } = mountRoom('guest-1')

    expect(wrapper.find('[data-action="start-game"]').exists()).toBe(false)
    expect(wrapper.get('[data-action="ready"]').text()).toContain('준비')
  })

  it('enables the ready control when a final waiting-room spectator is activated', async () => {
    const waitingParticipants = room.participants.map(participant => participant.actorId === 'guest-1'
      ? { ...participant, spectator: true }
      : participant)
    const { wrapper, store } = mountRoom('guest-1', false, {
      participantCount: 1,
      participants: waitingParticipants,
    })
    expect(wrapper.get('[data-action="ready"]').attributes('disabled')).toBeDefined()

    const promoted = store.snapshot?.participants.find(participant => participant.actorId === 'guest-1')
    if (!promoted) throw new Error('guest fixture missing')
    promoted.spectator = false
    store.snapshot!.participantCount = 2
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-action="ready"]').attributes('disabled')).toBeUndefined()
  })

  it('renders the game shell while preserving participants, chat, and read-only settings', () => {
    const { wrapper } = mountRoom('host-1', false, {
      status: 'PLAYING',
      game: {
        publicState: {
          gameType: 'LIAR', round: 1, phase: 'ROLE_REVEAL', deadlineAt: '2026-08-27T00:00:05Z',
          hints: [], submittedPlayerIds: [], scores: { 'host-1': 0, 'guest-1': 0 },
        },
        privateState: { role: 'CITIZEN', category: '음식', word: '붕어빵', hintSubmitted: false, voteSubmitted: false },
      },
    })

    expect(wrapper.get('[data-region="game-shell"]').text()).toContain('라이어 게임')
    expect(wrapper.get('[data-region="participants"]').text()).toContain('참가감자')
    expect(wrapper.get('[data-region="settings"] select').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-region="chat"] label').text()).toContain('메시지 입력')
  })

  it('forwards backend-compatible liar action envelopes from the mounted game UI', async () => {
    const { wrapper, store } = mountRoom('host-1', false, { status: 'PLAYING', game: liarGame('HINTING') })
    store.sendGameAction = vi.fn()

    await wrapper.get('textarea#liar-hint').setValue('따뜻해요')
    await wrapper.get('[aria-labelledby="hint-title"] form').trigger('submit')
    expect(store.sendGameAction).toHaveBeenLastCalledWith('HINT_SUBMIT', { hint: '따뜻해요' })

    store.snapshot!.game = liarGame('DISCUSSING', undefined, ['guest-1'])
    await wrapper.vm.$nextTick()
    await wrapper.get('button[aria-label="토론 종료 찬성"]').trigger('click')
    expect(store.sendGameAction).toHaveBeenLastCalledWith('DISCUSSION_END_VOTE', { agree: true })

    store.snapshot!.game = liarGame('VOTING')
    await wrapper.vm.$nextTick()
    await wrapper.get('input[value="guest-1"]').setValue()
    await wrapper.get('form[data-panel="vote"]').trigger('submit')
    expect(store.sendGameAction).toHaveBeenLastCalledWith('VOTE_SUBMIT', { targetActorId: 'guest-1' })

    store.snapshot!.game = liarGame('REVOTING')
    await wrapper.vm.$nextTick()
    await wrapper.get('input[value="guest-1"]').setValue()
    await wrapper.get('form[data-panel="vote"]').trigger('submit')
    expect(store.sendGameAction).toHaveBeenLastCalledWith('REVOTE_SUBMIT', { targetActorId: 'guest-1' })

    store.snapshot!.game = liarGame('LIAR_GUESSING')
    await wrapper.vm.$nextTick()
    await wrapper.get('input[name="word"]').setValue('붕어빵')
    await wrapper.get('form').trigger('submit')
    expect(store.sendGameAction).toHaveBeenLastCalledWith('LIAR_GUESS_SUBMIT', { answer: '붕어빵' })
  })

  it('does not render waiting-room readiness or start controls after the room closes', () => {
    const { wrapper } = mountRoom('host-1', false, { status: 'CLOSED', canStart: true })

    expect(wrapper.find('[data-action="start-game"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="ready"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('WAITING ROOM')
  })

  it('shows settings read-only to non-host participants', () => {
    const { wrapper } = mountRoom('guest-1')

    expect(wrapper.get('[data-region="settings"]').find('select').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-region="settings"]').text()).toContain('방장만 변경')
  })

  it('uses one chat-or-answer compatible submission contract and shows mobile unread count', async () => {
    const { wrapper, store } = mountRoom()
    store.unreadChatCount = 2
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-action="open-mobile-chat"]').text()).toContain('2')
    await wrapper.get('[data-region="chat"] input').setValue('정답처럼 보이지만 지금은 채팅')
    await wrapper.get('[data-region="chat"] form').trigger('submit')

    expect(store.sendChat).toHaveBeenCalledWith('정답처럼 보이지만 지금은 채팅')
    expect((wrapper.get('[data-region="chat"] input').element as HTMLInputElement).value).toBe('')
  })

  it('closes the mobile chat sheet with Escape and restores the opener focus', async () => {
    const { wrapper } = mountRoom('host-1', true)
    const opener = wrapper.get('[data-action="open-mobile-chat"]')
    ;(opener.element as HTMLElement).focus()
    await opener.trigger('click')

    expect(wrapper.get('[role="dialog"]').attributes('aria-label')).toBe('방 채팅')
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('joins immediately when the same routed component receives a different room code', async () => {
    const { wrapper, store } = mountRoom()

    await wrapper.setProps({ code: '654321' })

    expect(store.join).toHaveBeenCalledWith('654321', '')
  })

  it('disables ready, settings, and every chat composer while realtime is unavailable', async () => {
    const { wrapper, store } = mountRoom()
    store.chatOpen = true
    store.connection = 'reconnecting'
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-action="start-game"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-region="settings"] select').attributes('disabled')).toBeDefined()
    const chatInputs = wrapper.findAll('[data-region="chat"] input')
    expect(chatInputs).toHaveLength(2)
    expect(chatInputs.every(input => input.attributes('disabled') !== undefined)).toBe(true)
  })

  it('gives simultaneously mounted desktop and mobile chat instances unique accessible IDs', async () => {
    const { wrapper, store } = mountRoom()
    store.chatOpen = true
    await wrapper.vm.$nextTick()

    const chats = wrapper.findAll('[data-region="chat"]')
    const inputIds = chats.map(chat => chat.get('input').attributes('id'))
    const titleIds = chats.map(chat => chat.get('h2').attributes('id'))

    expect(new Set(inputIds).size).toBe(2)
    expect(new Set(titleIds).size).toBe(2)
    chats.forEach((chat, index) => {
      expect(chat.get('label').attributes('for')).toBe(inputIds[index])
      expect(chat.attributes('aria-labelledby')).toBe(titleIds[index])
    })
  })

  it('offers an explicit snapshot retry after recovery enters the failed state', async () => {
    const { wrapper, store } = mountRoom()
    store.connection = 'failed'
    store.error = '방 상태를 다시 불러오지 못했습니다.'
    store.retryRecovery = vi.fn(async () => undefined)
    await wrapper.vm.$nextTick()

    await wrapper.get('[data-action="retry-room-recovery"]').trigger('click')

    expect(store.retryRecovery).toHaveBeenCalledOnce()
  })

  it('offers an explicit room-transition retry when cleanup leaves the previous snapshot visible', async () => {
    const { wrapper, store } = mountRoom()
    await wrapper.setProps({ code: '654321' })
    store.connection = 'failed'
    store.error = '이전 방을 정리하지 못했습니다.'
    await wrapper.vm.$nextTick()

    await wrapper.get('[data-action="retry-room-transition"]').trigger('click')

    expect(store.join).toHaveBeenNthCalledWith(2, '654321', '')
  })
})
