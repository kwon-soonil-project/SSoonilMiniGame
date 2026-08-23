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
      sequence: 1,
    }]

    const wrapper = mount(LobbyView, { global: { plugins: [pinia] } })

    expect(wrapper.text()).toContain('대기 중')
    expect(wrapper.text()).toContain('비밀번호 필요')
    expect(wrapper.text()).toContain('3 / 8명')
    expect(wrapper.findAll('dt').some(term => term.text() === '방장')).toBe(true)
    expect(wrapper.findAll('dd').some(detail => detail.text() === '방장감자')).toBe(true)
    expect(wrapper.get('[data-password-state]').text()).toContain('비밀번호 필요')
    expect(wrapper.get('[data-password-state] [aria-hidden="true"]').attributes('aria-hidden')).toBe('true')
  })

  it('opens and closes the create-room dialog with the keyboard', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useLobbyStore()
    store.initialize = vi.fn(async () => undefined)
    const wrapper = mount(LobbyView, { attachTo: document.body, global: { plugins: [pinia] } })

    const opener = wrapper.get('button[data-action="create-room"]')
    ;(opener.element as HTMLElement).focus()
    await opener.trigger('click')
    expect(wrapper.get('[role="dialog"]').attributes('aria-labelledby')).toBe('create-room-title')
    expect(wrapper.get('label[for="room-title"]').text()).toContain('방 제목')
    const focus = vi.spyOn(
      wrapper.get('button[data-action="create-room"]').element as HTMLElement,
      'focus',
    )

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await vi.waitFor(() => expect(focus).toHaveBeenCalled())
    wrapper.unmount()
  })

  it('contains keyboard focus inside the create-room dialog', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useLobbyStore()
    store.initialize = vi.fn(async () => undefined)
    const wrapper = mount(LobbyView, { attachTo: document.body, global: { plugins: [pinia] } })
    await wrapper.get('button[data-action="create-room"]').trigger('click')
    const closeButton = wrapper.get('button[aria-label="방 만들기 닫기"]')
    ;(closeButton.element as HTMLElement).focus()

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab', shiftKey: true })

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"] button[type="submit"]').element)
    wrapper.unmount()
  })

  it('clears a cancelled plaintext room password before the dialog reopens', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useLobbyStore()
    store.initialize = vi.fn(async () => undefined)
    const wrapper = mount(LobbyView, { global: { plugins: [pinia] } })
    await wrapper.get('button[data-action="create-room"]').trigger('click')
    await wrapper.get('#room-password-enabled').setValue(true)
    await wrapper.get('#room-password').setValue('취소할비밀번호')

    await wrapper.get('button.secondary').trigger('click')
    await wrapper.get('button[data-action="create-room"]').trigger('click')

    expect((wrapper.get('#room-password-enabled').element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.find('#room-password').exists()).toBe(false)
  })

  it('shows a persistent realtime warning with an independent retry action', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useLobbyStore()
    store.initialize = vi.fn(async () => undefined)
    store.realtimeStatus = 'failed'
    store.realtimeWarning = '실시간 업데이트가 비활성화되었어요.'
    store.retryRealtime = vi.fn(async () => undefined)
    const wrapper = mount(LobbyView, { global: { plugins: [pinia] } })

    expect(wrapper.get('[data-realtime-warning]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-realtime-warning]').text()).toContain('실시간 업데이트가 비활성화')
    await wrapper.get('button[data-action="retry-realtime"]').trigger('click')
    expect(store.retryRealtime).toHaveBeenCalledOnce()
  })
})
