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
      isHost: true,
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

  it('makes a mid-round promoted participant eligible for voting', async () => {
    const wrapper = mountLiar()
    const waiting = { actorId: 'waiting', nickname: '승격감자', ready: false, spectator: true }
    await wrapper.setProps({
      publicState: {
        gameType: 'LIAR', round: 1, phase: 'VOTING', deadlineAt: '2026-08-27T00:00:05Z',
        hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0, waiting: 0 },
      },
      participants: [...participants, waiting],
    })
    expect(wrapper.find('input[value="waiting"]').exists()).toBe(false)

    await wrapper.setProps({ participants: [...participants, { ...waiting, spectator: false }] })

    expect(wrapper.find('input[value="waiting"]').exists()).toBe(true)
  })

  it('offers exactly the public tied candidates during a revote while excluding self', async () => {
    const wrapper = mountLiar()
    const third = { actorId: 'third', nickname: '제외감자', ready: true, spectator: false }
    await wrapper.setProps({
      publicState: {
        gameType: 'LIAR', round: 1, phase: 'REVOTING', deadlineAt: '2026-08-27T00:00:05Z',
        hints: [], hintStatuses: [], submittedPlayerIds: [], scores: { host: 0, guest: 0, third: 0 },
        revoteCandidates: ['host', 'guest'],
      },
      participants: [...participants, third],
    })

    expect(wrapper.find('input[value="host"]').exists()).toBe(false)
    expect(wrapper.find('input[value="guest"]').exists()).toBe(true)
    expect(wrapper.find('input[value="third"]').exists()).toBe(false)
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

  it('reveals approval to a guest only after the public discussion proposal acknowledgement', async () => {
    const wrapper = mountLiar()
    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'DISCUSSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    }, actorId: 'guest', isHost: false })
    expect(wrapper.find('button[aria-label="토론 종료 제안"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="토론 종료 찬성"]').exists()).toBe(false)

    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'DISCUSSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: ['host'], scores: { host: 0, guest: 0 },
    } })
    expect(wrapper.get('button[aria-label="토론 종료 찬성"]').attributes('disabled')).toBeUndefined()
  })

  it('renders authoritative submitted and skipped hint history in turn order', async () => {
    const wrapper = mountLiar()
    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'DISCUSSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [{ playerId: 'host', text: '따뜻해요' }],
      hintStatuses: [
        { playerId: 'host', status: 'SUBMITTED' },
        { playerId: 'departed', status: 'SKIPPED' },
      ],
      submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    } })

    expect(wrapper.get('[data-region="hint-history"]').text()).toContain('방장감자: 따뜻해요')
    expect(wrapper.get('[data-region="hint-history"]').text()).toContain('departed: 힌트 건너뜀')
  })

  it('exposes the phase-specific guess and return actions by their accessible names', async () => {
    const wrapper = mountLiar()

    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'LIAR_GUESSING', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
    } })
    expect(wrapper.get('button[aria-label="제시어 추측"]')).toBeDefined()

    await wrapper.setProps({ publicState: {
      gameType: 'LIAR', round: 1, phase: 'GAME_RESULT', deadlineAt: '2026-08-27T00:00:05Z',
      hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 }, liarId: 'host', answer: '붕어빵',
      roundResult: { winner: 'LIAR', invalidated: false, liarGuessedCorrectly: false },
      finalScores: [
        { actorId: 'departed', nickname: '떠난감자', score: 5, rank: 1, roundsPlayed: 1 },
        { actorId: 'guest', nickname: '참가감자', score: 5, rank: 1, roundsPlayed: 2 },
        { actorId: 'host', nickname: '방장감자', score: 2, rank: 3, roundsPlayed: 2 },
      ],
    } })
    expect(wrapper.get('button[aria-label="대기방으로 돌아가기"]')).toBeDefined()
    expect(wrapper.get('[data-region="final-ranking"]').text()).toContain('1위 떠난감자 5점')
    expect(wrapper.get('[data-region="final-ranking"]').text()).toContain('1위 참가감자 5점')
    expect(wrapper.get('[data-region="final-ranking"]').text()).toContain('3위 방장감자 2점')
    expect(wrapper.text()).toContain('라이어: 방장감자')
    expect(wrapper.text()).toContain('제시어: 붕어빵')

    await wrapper.setProps({ isHost: false })
    expect(wrapper.find('button[aria-label="대기방으로 돌아가기"]').exists()).toBe(false)
  })

  it('announces a successful liar comeback distinctly from survival and a citizen win', () => {
    const comeback = mount(LiarGameView, { props: {
      publicState: {
        gameType: 'LIAR', round: 1, phase: 'ROUND_RESULT', deadlineAt: '2026-08-27T00:00:05Z',
        hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
        roundResult: { winner: 'LIAR', invalidated: false, accusedId: 'host', liarGuessedCorrectly: true },
      },
      privateState: null, participants, actorId: 'host', isHost: true, connected: true,
    } })
    expect(comeback.text()).toContain('라이어가 제시어를 맞혀 역전했습니다.')

    const survival = mount(LiarGameView, { props: {
      publicState: {
        gameType: 'LIAR', round: 1, phase: 'ROUND_RESULT', deadlineAt: '2026-08-27T00:00:05Z',
        hints: [], submittedPlayerIds: [], scores: { host: 0, guest: 0 },
        roundResult: { winner: 'LIAR', invalidated: false, liarGuessedCorrectly: false },
      },
      privateState: null, participants, actorId: 'host', isHost: true, connected: true,
    } })
    expect(survival.text()).toContain('라이어가 살아남아 승리했습니다.')
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
