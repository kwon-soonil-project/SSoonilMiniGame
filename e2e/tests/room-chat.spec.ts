import { expect, test } from '@playwright/test'
import { createPrivateRoom, createPublicRoom, joinAsGuest } from './helpers'

test('two guests discover a public room, ready up, chat, and recover after reload', async ({ browser }) => {
  const host = await browser.newContext()
  const guest = await browser.newContext()

  try {
    const hostPage = await host.newPage()
    const guestPage = await guest.newPage()

    await joinAsGuest(hostPage, '민수')
    const title = '퇴근 후 딱 한 판!'
    const code = await createPublicRoom(hostPage, title)

    await joinAsGuest(guestPage, '수진')
    const roomCard = guestPage.getByRole('article').filter({ hasText: title })
    await expect(roomCard).toContainText('바로 입장')
    await roomCard.getByRole('link', { name: '방 입장하기' }).click()
    await expect(guestPage).toHaveURL(new RegExp(`/rooms/${code}$`))
    await expect(guestPage.getByRole('status')).toContainText('실시간 연결됨')

    await expect(hostPage.getByRole('listitem').filter({ hasText: '수진' })).toBeVisible()
    await guestPage.getByRole('button', { name: '준비하기' }).click()
    await expect(hostPage.getByRole('listitem').filter({ hasText: '민수' })).toContainText('방장')
    await expect(hostPage.getByRole('listitem').filter({ hasText: '수진' })).toContainText('준비 완료')

    await guestPage.getByLabel('메시지 입력').fill('다들 준비됐어?')
    await guestPage.getByRole('button', { name: '전송' }).click()
    await expect(hostPage.getByText('다들 준비됐어?')).toBeVisible()

    await guestPage.reload()
    await expect(guestPage.getByRole('status')).toContainText('실시간 연결됨')
    await guestPage.getByLabel('메시지 입력').fill('재접속도 완료!')
    await guestPage.getByRole('button', { name: '전송' }).click()
    await expect(hostPage.getByText('재접속도 완료!')).toBeVisible()
  } finally {
    await host.close()
    await guest.close()
  }
})

test('a private-room host navigates and reloads without re-entering the password', async ({ page }) => {
  await joinAsGuest(page, '비밀방장')
  const code = await createPrivateRoom(page, '초대 전용 방', '1234')

  await expect(page).toHaveURL(new RegExp(`/rooms/${code}$`))
  await expect(page.getByRole('status')).toContainText('실시간 연결됨')
  await page.reload()
  await expect(page.getByRole('status')).toContainText('실시간 연결됨')
  await expect(page.getByText('이 방에 입장하려면 비밀번호가 필요해요.')).not.toBeVisible()
})
