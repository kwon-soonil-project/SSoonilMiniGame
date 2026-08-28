import { expect, test, type Page } from '@playwright/test'
import {
  accusationMatrix,
  assertPrivateStateIsNotSerializedAcrossPlayers,
  awaitCrossPageParticipants,
  configureLiarGame,
  createGuestPages,
  createPublicRoom,
  expectAll,
  expectGameResultAndReturn,
  firstTieMatrix,
  joinRoomByCode,
  proposeAndApproveDiscussionEnd,
  readDisplayedRoles,
  readyGuestsAndStart,
  refreshAndExpectPrivateStateRestored,
  submitHintsInDisplayedOrder,
  submitLiarGuess,
  submitVoteMatrix,
  type DisplayedRoles,
} from './helpers'

const nicknames = ['민지', '준호', '서연', '현우']
const settings = { rounds: 1, actionSeconds: 15, discussionSeconds: 60, category: 'all' } as const
const publicSafeHints = ['첫인상이 또렷해요', '주변에서 종종 보여요', '친구들과 이야기해요', '천천히 생각하면 떠올라요']

test('four guests cover private reconnect, both liar guesses, first tie revote, and waiting-room return', async ({ browser }) => {
  const players = await createGuestPages(browser, nicknames)
  const browserMessages: string[] = []
  players.pages.forEach(page => page.on('console', message => browserMessages.push(message.text())))

  try {
    const [host, ...guests] = players.pages
    const code = await createPublicRoom(host, `라이어-${Date.now()}`)
    for (const guest of guests) await joinRoomByCode(guest, code)
    await awaitCrossPageParticipants(players.pages, nicknames)

    const failedGuessRoles = await beginRound(players.pages, host, guests)
    await refreshAndExpectPrivateStateRestored(players.pages, nicknames, failedGuessRoles)
    await assertPrivateStateIsNotSerializedAcrossPlayers(players.pages, nicknames, failedGuessRoles, browserMessages)
    await finishHintsAndDiscussion(players.pages, host, guests)
    await submitVoteMatrix(players.pages, nicknames, accusationMatrix(failedGuessRoles))
    await submitLiarGuess(players.pages, nicknames, failedGuessRoles, '정답과무관한고정오답')
    await expectGameResultAndReturn(host, players.pages, '시민')

    const successfulGuessRoles = await beginRound(players.pages, host, guests)
    await finishHintsAndDiscussion(players.pages, host, guests)
    await submitVoteMatrix(players.pages, nicknames, accusationMatrix(successfulGuessRoles))
    await submitLiarGuess(players.pages, nicknames, successfulGuessRoles, successfulGuessRoles.word)
    await expectGameResultAndReturn(host, players.pages, '라이어')

    const revoteRoles = await beginRound(players.pages, host, guests)
    await finishHintsAndDiscussion(players.pages, host, guests)
    await submitVoteMatrix(players.pages, nicknames, firstTieMatrix(revoteRoles))
    await expectAll(players.pages, page => page.getByRole('heading', { name: '재투표' }))
    await submitVoteMatrix(players.pages, nicknames, accusationMatrix(revoteRoles))
    await submitLiarGuess(players.pages, nicknames, revoteRoles, '정답과무관한재투표오답')
    await expectGameResultAndReturn(host, players.pages, '시민')

    expect(browserMessages.some(message => /"(?:role|word|targetActorId)"/.test(message))).toBe(false)
  } finally {
    await players.close()
  }
})

async function beginRound(
  pages: Page[],
  host: Page,
  guests: Page[],
): Promise<DisplayedRoles> {
  await configureLiarGame(host, pages, settings)
  await readyGuestsAndStart(host, guests, pages)
  return readDisplayedRoles(pages, nicknames)
}

async function finishHintsAndDiscussion(
  pages: Page[],
  host: Page,
  guests: Page[],
): Promise<void> {
  await submitHintsInDisplayedOrder(pages, nicknames, publicSafeHints)
  await proposeAndApproveDiscussionEnd(host, guests, pages)
}
