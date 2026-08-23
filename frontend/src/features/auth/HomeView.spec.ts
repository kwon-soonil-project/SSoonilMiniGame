import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import HomeView from './HomeView.vue'
import { useAuthStore } from './authStore'

describe('HomeView', () => {
  it('offers Google login and an accessible guest dialog', async () => {
    const wrapper = mount(HomeView, { global: { plugins: [createPinia()] } })

    expect(wrapper.get('a[href="/oauth2/authorization/google"]').text()).toContain('Google')
    await wrapper.get('button[data-action="open-guest"]').trigger('click')

    expect(wrapper.get('[role="dialog"]').attributes('aria-labelledby')).toBe('guest-dialog-title')
    expect(wrapper.get('label[for="guest-nickname"]').text()).toContain('닉네임')
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('restores focus to the guest opener when the dialog closes', async () => {
    const wrapper = mount(HomeView, { attachTo: document.body, global: { plugins: [createPinia()] } })
    const opener = wrapper.get('button[data-action="open-guest"]')
    ;(opener.element as HTMLElement).focus()
    await opener.trigger('click')
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await wrapper.vm.$nextTick()

    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('contains keyboard focus inside the guest dialog', async () => {
    const wrapper = mount(HomeView, { attachTo: document.body, global: { plugins: [createPinia()] } })
    await wrapper.get('button[data-action="open-guest"]').trigger('click')
    const closeButton = wrapper.get('button[aria-label="게스트 창 닫기"]')
    ;(closeButton.element as HTMLElement).focus()

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab', shiftKey: true })

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"] button[type="submit"]').element)
    wrapper.unmount()
  })

  it('offers an explicit retry when current-session initialization failed', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.error = '인증 서버를 확인해 주세요.'
    const wrapper = mount(HomeView, { global: { plugins: [pinia] } })

    expect(wrapper.get('[data-auth-error]').text()).toContain('인증 서버')
    expect(wrapper.get('button[data-action="retry-auth"]').text()).toContain('다시 시도')
  })
})
