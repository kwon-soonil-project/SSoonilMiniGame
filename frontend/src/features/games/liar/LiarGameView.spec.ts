import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LiarGameView from './LiarGameView.vue'

const participants = [
  { actorId: 'host', nickname: '방장감자', ready: true, spectator: false },
  { actorId: 'guest', nickname: '참가감자', ready: true, spectator: false },
]

function mountLiar() {
  return mount(LiarGameView, {
    props: {
      publicState: {
        gameType: 'LIAR', round: 1, phase: 'ROLE_REVEAL', deadlineAt: '2026-08-27T00:00:05Z',
        hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
      },
      privateState: { role: 'LIAR', category: '음식', word: '붕어빵', hintSubmitted: false, voteSubmitted: false },
      participants,
      actorId: 'host',
      connected: true,
    },
  })
}

describe('LiarGameView', () => {
  it('never renders the word for a liar', () => {
    const wrapper = mountLiar()

    expect(wrapper.text()).toContain('라이어')
    expect(wrapper.text()).toContain('음식')
    expect(wrapper.text()).not.toContain('붕어빵')
  })

  it('disables hint submission until it is the actor turn and while reconnecting', async () => {
    const wrapper = mountLiar()
    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'HINTING', deadlineAt: '2026-08-27T00:00:05Z', currentHinter: 'guest',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    } })

    expect(wrapper.get('button[aria-label="힌트 제출"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'HINTING', deadlineAt: '2026-08-27T00:00:05Z', currentHinter: 'host',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    }, connected: false })
    expect(wrapper.get('button[aria-label="힌트 제출"]').attributes('disabled')).toBeDefined()
  })

  it('excludes the actor from vote candidates and emits the backend vote envelope', async () => {
    const wrapper = mountLiar()
    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'VOTING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    } })

    expect(wrapper.text()).not.toContain('방장감자')
    await wrapper.get('input[value="guest"]').setValue()
    await wrapper.get('form[data-panel="vote"]').trigger('submit')

    expect(wrapper.emitted('action')).toEqual([[{ action: 'VOTE_SUBMIT', data: { targetActorId: 'guest' } }]])
  })

  it('disables voting until the private sidecar arrives and after either submission signal', async () => {
    const wrapper = mountLiar()
    const votingState = {
      gameType: 'LIAR' as const, round: 1, phase: 'VOTING' as const, deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    }
    await wrapper.setProps({ publicState: votingState, privateState: null })
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ privateState: { role: 'LIAR', category: '음식', word: '붕어빵', hintSubmitted: false, voteSubmitted: false } })
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()

    await wrapper.setProps({ publicState: { ...votingState, submittedPlayerIds: ['host'] } })
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ publicState: votingState, privateState: { role: 'LIAR', category: '음식', word: '붕어빵', hintSubmitted: false, voteSubmitted: true } })
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
  })

  it('exposes the phase-specific discussion, guess, and return actions by their accessible names', async () => {
    const wrapper = mountLiar()
    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'DISCUSSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    } })
    expect(wrapper.get('button[aria-label="토론 종료 제안"]')).toBeDefined()
    expect(wrapper.get('button[aria-label="토론 종료 찬성"]')).toBeDefined()

    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'LIAR_GUESSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    } })
    expect(wrapper.get('button[aria-label="제시어 추측"]')).toBeDefined()

    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'GAME_RESULT', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    } })
    expect(wrapper.get('button[aria-label="대기방으로 돌아가기"]')).toBeDefined()
  })

  it('disables discussion and guessing inputs after the actor has submitted', async () => {
    const wrapper = mountLiar()
    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'DISCUSSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: ['host'], scores: { host: 0, guest: 0 },
    } })
    expect(wrapper.get('button[aria-label="토론 종료 찬성"]').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'LIAR_GUESSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: ['host'], scores: { host: 0, guest: 0 },
    } })
    expect(wrapper.get('button[aria-label="제시어 추측"]').attributes('disabled')).toBeDefined()
  })

  it('announces the phase instead of each timer tick', () => {
    const wrapper = mountLiar()

    expect(wrapper.get('[data-region="phase-announcement"]').attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[data-region="timer"]').attributes('aria-live')).toBeUndefined()
  })
})
