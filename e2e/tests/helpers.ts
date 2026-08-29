import {
  expect,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from '@playwright/test'

export const E2E_EXPECT_TIMEOUT = 20_000

export interface GuestPlayers {
  contexts: BrowserContext[]
  pages: Page[]
  nicknames: string[]
  close(): Promise<void>
}

export interface LiarSettings {
  rounds: number
  actionSeconds: number
  discussionSeconds: number
  category: 'all' | 'food' | 'animal' | 'job' | 'place' | 'household' | 'sports' | 'transport' | 'hobby'
}

export interface DisplayedRoles {
  liar: string
  citizens: string[]
  word: string
}

export type VoteTargetMatrix = Record<string, string>

export async function joinAsGuest(page: Page, nickname: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '게스트로 시작' }).click()
  await page.getByLabel('닉네임').fill(nickname)
  await page.getByRole('button', { name: '로비로 입장' }).click()
  await expect(page).toHaveURL(/\/lobby$/, { timeout: E2E_EXPECT_TIMEOUT })
  await expect(page.getByText(`${nickname}님, 반가워요!`)).toBeVisible({ timeout: E2E_EXPECT_TIMEOUT })
}

export async function createGuestPages(browser: Browser, nicknames: string[]): Promise<GuestPlayers> {
  const contexts: BrowserContext[] = []
  const pages: Page[] = []
  try {
    for (const nickname of nicknames) {
      const context = await browser.newContext()
      contexts.push(context)
      const page = await context.newPage()
      pages.push(page)
      await joinAsGuest(page, nickname)
    }
  } catch (cause) {
    await Promise.allSettled(contexts.map(context => context.close()))
    throw cause
  }
  return {
    contexts,
    pages,
    nicknames: [...nicknames],
    close: async () => {
      await Promise.allSettled(contexts.map(context => context.close()))
    },
  }
}

export async function createPublicRoom(page: Page, title: string): Promise<string> {
  await page.getByRole('button', { name: '방 만들기', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '방 만들기' })
  await dialog.getByLabel('방 제목').fill(title)
  await dialog.getByLabel('첫 게임').selectOption('LIAR')
  await expect(dialog.getByLabel('공개 로비에 표시')).toBeChecked()
  await dialog.getByRole('button', { name: '방 만들기', exact: true }).click()
  await expect(page).toHaveURL(/\/rooms\/\d{6}$/, { timeout: E2E_EXPECT_TIMEOUT })
  await expect(page.getByRole('status')).toContainText('실시간 연결됨', { timeout: E2E_EXPECT_TIMEOUT })
  return page.url().split('/').at(-1) as string
}

export async function joinRoomByCode(page: Page, code: string): Promise<void> {
  const quickEntry = page.getByRole('region', { name: '방 코드로 바로 입장' })
  await quickEntry.getByLabel('방 코드').fill(code)
  await quickEntry.getByRole('button', { name: '입장', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/rooms/${code}$`), { timeout: E2E_EXPECT_TIMEOUT })
  await expect(page.getByRole('status')).toContainText('실시간 연결됨', { timeout: E2E_EXPECT_TIMEOUT })
}

export async function expectAll(pages: Page[], locate: (page: Page) => Locator): Promise<void> {
  await Promise.all(pages.map(page => expect(locate(page)).toBeVisible({ timeout: E2E_EXPECT_TIMEOUT })))
}

export async function awaitCrossPageParticipants(pages: Page[], nicknames: string[]): Promise<void> {
  await Promise.all(pages.flatMap(page => nicknames.map(nickname =>
    expect(page.getByRole('listitem').filter({ hasText: nickname })).toBeVisible({ timeout: E2E_EXPECT_TIMEOUT }),
  )))
}

export async function configureLiarGame(
  host: Page,
  guests: Page[],
  pages: Page[],
  settings: LiarSettings,
): Promise<void> {
  for (const guest of guests) {
    await guest.getByRole('button', { name: '준비하기' }).click()
  }
  await expect(host.getByRole('button', { name: '게임 시작' })).toBeEnabled({ timeout: E2E_EXPECT_TIMEOUT })

  const panel = host.getByRole('region', { name: '게임 설정' })
  await panel.getByLabel('라운드').fill(String(settings.rounds))
  await panel.getByLabel('행동 시간(초)').fill(String(settings.actionSeconds))
  await panel.getByLabel('토론 시간(초)').fill(String(settings.discussionSeconds))
  await panel.getByLabel('카테고리').selectOption(settings.category)
  await panel.getByRole('button', { name: '설정 저장' }).click()

  const summary = `${settings.rounds}라운드 · 행동 ${settings.actionSeconds}초 · 토론 ${settings.discussionSeconds}초`
  await expectAll(pages, page => page.getByText(summary, { exact: true }))
  await expect(host.getByRole('button', { name: '게임 시작' })).toBeDisabled({ timeout: E2E_EXPECT_TIMEOUT })
  await Promise.all(guests.map(page =>
    expect(page.getByRole('button', { name: '준비하기' })).toBeEnabled({ timeout: E2E_EXPECT_TIMEOUT }),
  ))
}

export async function readyGuestsAndStart(host: Page, guests: Page[], allPages: Page[]): Promise<void> {
  for (const guest of guests) {
    await guest.getByRole('button', { name: '준비하기' }).click()
  }
  await expect(host.getByRole('button', { name: '게임 시작' })).toBeEnabled({ timeout: E2E_EXPECT_TIMEOUT })
  await host.getByRole('button', { name: '게임 시작' }).click()
  await expectAll(allPages, page => page.getByRole('heading', { name: /내 역할: (라이어|시민)/ }))
}

export async function readDisplayedRoles(pages: Page[], nicknames: string[]): Promise<DisplayedRoles> {
  const liarIndex = await findPageIndex(pages, page => page.getByRole('heading', { name: '내 역할: 라이어' }))
  const citizenIndexes = pages.map((_, index) => index).filter(index => index !== liarIndex)
  const wordText = await pages[citizenIndexes[0]].getByText(/^제시어:/).innerText()
  const word = wordText.replace(/^제시어:\s*/, '').trim()
  expect(word.length > 0).toBe(true)
  await Promise.all(citizenIndexes.map(index =>
    expect(pages[index].getByText(/^제시어:/)).toContainText(word, { timeout: E2E_EXPECT_TIMEOUT }),
  ))
  return {
    liar: nicknames[liarIndex],
    citizens: citizenIndexes.map(index => nicknames[index]),
    word,
  }
}

export async function refreshAndExpectPrivateStateRestored(
  pages: Page[],
  nicknames: string[],
  roles: DisplayedRoles,
): Promise<void> {
  const citizenIndex = nicknames.indexOf(roles.citizens[0])
  const page = pages[citizenIndex]
  const phase = (await page.locator('[data-region="phase-announcement"]').innerText()).trim()
  await page.reload()
  await expect(page.getByRole('status')).toContainText('실시간 연결됨', { timeout: E2E_EXPECT_TIMEOUT })
  await expect(page.getByRole('heading', { name: '내 역할: 시민' })).toBeVisible({ timeout: E2E_EXPECT_TIMEOUT })
  await expect(page.getByText(/^제시어:/)).toContainText(roles.word, { timeout: E2E_EXPECT_TIMEOUT })
  await expect(page.locator('[data-region="phase-announcement"]')).toHaveText(phase, { timeout: E2E_EXPECT_TIMEOUT })
}

export async function assertPrivateStateIsNotSerializedAcrossPlayers(
  pages: Page[],
  nicknames: string[],
  roles: DisplayedRoles,
  browserMessages: string[],
): Promise<void> {
  const liarPage = pages[nicknames.indexOf(roles.liar)]
  await expect(liarPage.getByText(/^제시어:/)).toHaveCount(0)
  const liarMarkup = await liarPage.content()
  expect(liarMarkup.includes(roles.word)).toBe(false)
  expect(browserMessages.some(message => message.includes(roles.word))).toBe(false)

  for (let index = 0; index < pages.length; index += 1) {
    const expectedRole = nicknames[index] === roles.liar ? '라이어' : '시민'
    await expect(pages[index].getByRole('heading', { name: `내 역할: ${expectedRole}` })).toHaveCount(1)
    await expect(pages[index].getByRole('heading', { name: `내 역할: ${expectedRole === '라이어' ? '시민' : '라이어'}` })).toHaveCount(0)
    const serializedStorage = await pages[index].evaluate(() => JSON.stringify({
      local: Object.entries(localStorage),
      session: Object.entries(sessionStorage),
    }))
    expect(serializedStorage.includes('privateState')).toBe(false)
    expect(serializedStorage.includes('voteSubmitted')).toBe(false)
    expect(serializedStorage.includes(roles.word)).toBe(false)
  }
}

export async function submitHintsInDisplayedOrder(
  pages: Page[],
  nicknames: string[],
  hints: string[],
): Promise<void> {
  if (hints.length !== pages.length) throw new Error('One public-safe hint is required per player')
  await expectAll(pages, page => page.getByRole('heading', { name: '힌트 차례' }))
  for (const hint of hints) {
    const turnText = await pages[0].getByText(/^현재 힌트 차례:/).innerText()
    const nickname = nicknames.find(candidate => turnText.includes(candidate))
    if (!nickname) throw new Error('Displayed hint turn did not match a player nickname')
    const activePage = pages[nicknames.indexOf(nickname)]
    await expect(activePage.getByLabel('힌트')).toBeEnabled({ timeout: E2E_EXPECT_TIMEOUT })
    await activePage.getByLabel('힌트').fill(hint)
    await activePage.getByRole('button', { name: '힌트 제출' }).click()
    await expect.poll(async () => {
      const discussing = await pages[0].getByRole('heading', { name: '토론 시간' }).isVisible()
      if (discussing) return 'discussion'
      return (await pages[0].getByText(/^현재 힌트 차례:/).innerText()).trim()
    }, { timeout: E2E_EXPECT_TIMEOUT }).not.toBe(turnText.trim())
  }
  await expectAll(pages, page => page.getByRole('heading', { name: '토론 시간' }))
}

export async function proposeAndApproveDiscussionEnd(host: Page, approvers: Page[], pages: Page[]): Promise<void> {
  await host.getByRole('button', { name: '토론 종료 제안' }).click()
  await expect(host.getByRole('button', { name: '토론 종료 제안' })).toBeDisabled({ timeout: E2E_EXPECT_TIMEOUT })
  const requiredApprovers = approvers.slice(0, 2)
  await Promise.all(requiredApprovers.map(approver =>
    expect(approver.getByRole('button', { name: '토론 종료 찬성' })).toBeEnabled({ timeout: E2E_EXPECT_TIMEOUT }),
  ))
  for (const approver of requiredApprovers) {
    await approver.getByRole('button', { name: '토론 종료 찬성' }).click()
  }
  await expectAll(pages, page => page.getByRole('heading', { name: '투표', exact: true }))
}

export async function submitVoteMatrix(
  pages: Page[],
  nicknames: string[],
  matrix: VoteTargetMatrix,
): Promise<void> {
  expect(Object.keys(matrix).sort()).toEqual([...nicknames].sort())
  for (let index = 0; index < pages.length; index += 1) {
    const voter = nicknames[index]
    const target = matrix[voter]
    if (!target || target === voter) throw new Error('Vote matrix must target another displayed player')
    await pages[index].getByRole('radio', { name: target }).check()
    await pages[index].getByRole('button', { name: '투표 제출' }).click()
  }
}

export function accusationMatrix(roles: DisplayedRoles): VoteTargetMatrix {
  return Object.fromEntries([
    [roles.liar, roles.citizens[0]],
    ...roles.citizens.map(citizen => [citizen, roles.liar]),
  ])
}

export function firstTieMatrix(roles: DisplayedRoles): VoteTargetMatrix {
  const [first, second, third] = roles.citizens
  return {
    [roles.liar]: first,
    [first]: roles.liar,
    [second]: roles.liar,
    [third]: first,
  }
}

export async function submitLiarGuess(
  pages: Page[],
  nicknames: string[],
  roles: DisplayedRoles,
  answer: string,
): Promise<void> {
  await expectAll(pages, page => page.getByRole('heading', { name: '제시어 추측' }))
  const liarPage = pages[nicknames.indexOf(roles.liar)]
  await liarPage.getByLabel('제시어', { exact: true }).fill(answer)
  await liarPage.getByRole('button', { name: '제시어 추측' }).click()
}

export async function expectGameResultAndReturn(
  host: Page,
  pages: Page[],
  expectedWinner: '라이어' | '시민',
): Promise<void> {
  await expectAll(pages, page => page.getByRole('heading', { name: '라운드 결과' }))
  const outcome = expectedWinner === '라이어'
    ? '라이어가 제시어를 맞혀 역전했습니다.'
    : `${expectedWinner}이 승리했습니다.`
  await expectAll(pages, page => page.getByText(outcome))
  await expectAll(pages, page => page.getByRole('heading', { name: '최종 결과' }))
  await host.getByRole('button', { name: '대기방으로 돌아가기' }).click()
  await expect(host.getByRole('button', { name: '게임 시작' })).toBeVisible({ timeout: E2E_EXPECT_TIMEOUT })
  await Promise.all(pages.slice(1).map(page =>
    expect(page.getByRole('button', { name: '준비하기' })).toBeVisible({ timeout: E2E_EXPECT_TIMEOUT }),
  ))
}

async function findPageIndex(pages: Page[], locate: (page: Page) => Locator): Promise<number> {
  await expect.poll(async () => {
    const matches = await Promise.all(pages.map(async page => (await locate(page).count()) > 0))
    return matches.filter(Boolean).length
  }, { timeout: E2E_EXPECT_TIMEOUT }).toBe(1)
  const matches = await Promise.all(pages.map(async page => (await locate(page).count()) > 0))
  return matches.findIndex(Boolean)
}

export async function createPrivateRoom(page: Page, title: string, password: string): Promise<string> {
  await page.getByRole('button', { name: '방 만들기', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '방 만들기' })
  await dialog.getByLabel('방 제목').fill(title)
  await dialog.getByLabel('첫 게임').selectOption('LIAR')
  await dialog.getByLabel('공개 로비에 표시').uncheck()
  await dialog.getByLabel('비밀번호 사용').check()
  await dialog.getByRole('textbox', { name: '비밀번호', exact: true }).fill(password)
  await dialog.getByRole('button', { name: '방 만들기', exact: true }).click()
  await expect(page).toHaveURL(/\/rooms\/\d{6}$/)
  return page.url().split('/').at(-1) as string
}
