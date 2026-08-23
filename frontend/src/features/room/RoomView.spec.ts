import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../auth/authStore'
import RoomView from './RoomView.vue'
import { useRoomStore, type RoomSnapshot } from './roomStore'

const room: RoomSnapshot = {
  roomId: '00000000-0000-0000-0000-000000000701', code: '123456', title: '친구들과 한 판',
  visibility: 'PUBLIC', gameType: 'LIAR', status: 'WAITING', passwordProtected: false,
  participantCount: 2, maxParticipants: 10, hostId: 'host-1', sequence: 1,
  rounds: 3, actionSeconds: 30, discussionSeconds: 90, categoryPack: 'all',
  participants: [
    { actorId: 'host-1', nickname: '방장감자', ready: false, spectator: false },
    { actorId: 'guest-1', nickname: '참가감자', ready: false, spectator: false },
  ],
}

function mountRoom(actorId = 'host-1', attach = false) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.actor = { actorId, actorType: 'GUEST', nickname: actorId === 'host-1' ? '방장감자' : '참가감자', memberId: null }
  const store = useRoomStore()
  store.snapshot = structuredClone(room)
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
    expect(wrapper.get('button[data-action="ready"]').text()).toContain('준비')
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

    expect(wrapper.get('[data-action="ready"]').attributes('disabled')).toBeDefined()
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
