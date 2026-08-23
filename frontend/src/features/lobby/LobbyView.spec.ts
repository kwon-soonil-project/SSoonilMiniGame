import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import LobbyView from './LobbyView.vue'
import { useLobbyStore } from './lobbyStore'

describe('LobbyView', () => {
  it('shows room state, password protection, capacity, and host as text', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useLobbyStore()
    store.initialize = vi.fn(async () => undefined)
    store.rooms = [{
      roomId: 'room-1',
      code: '123456',
      title: '라이어 모임',
      gameType: 'LIAR',
      status: 'WAITING',
      passwordProtected: true,
      participantCount: 3,
      maxParticipants: 8,
      hostNickname: '방장감자',
    }]

    const wrapper = mount(LobbyView, { global: { plugins: [pinia] } })

    expect(wrapper.text()).toContain('대기 중')
    expect(wrapper.text()).toContain('비밀번호 필요')
    expect(wrapper.text()).toContain('3 / 8명')
    expect(wrapper.findAll('dt').some(term => term.text() === '방장')).toBe(true)
    expect(wrapper.findAll('dd').some(detail => detail.text() === '방장감자')).toBe(true)
  })

  it('opens and closes the create-room dialog with the keyboard', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useLobbyStore()
    store.initialize = vi.fn(async () => undefined)
    const wrapper = mount(LobbyView, { global: { plugins: [pinia] } })

    await wrapper.get('button[data-action="create-room"]').trigger('click')
    expect(wrapper.get('[role="dialog"]').attributes('aria-labelledby')).toBe('create-room-title')
    expect(wrapper.get('label[for="room-title"]').text()).toContain('방 제목')

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})
