import { expect, type Page } from '@playwright/test'

export async function joinAsGuest(page: Page, nickname: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '게스트로 시작' }).click()
  await page.getByLabel('닉네임').fill(nickname)
  await page.getByRole('button', { name: '로비로 입장' }).click()
  await expect(page).toHaveURL(/\/lobby$/)
  await expect(page.getByText(`${nickname}님, 반가워요!`)).toBeVisible()
}

export async function createPublicRoom(page: Page, title: string): Promise<string> {
  await page.getByRole('button', { name: '방 만들기', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '방 만들기' })
  await dialog.getByLabel('방 제목').fill(title)
  await dialog.getByLabel('첫 게임').selectOption('LIAR')
  await expect(dialog.getByLabel('공개 로비에 표시')).toBeChecked()
  await dialog.getByRole('button', { name: '방 만들기', exact: true }).click()
  await expect(page).toHaveURL(/\/rooms\/\d{6}$/)
  await expect(page.getByRole('status')).toContainText('실시간 연결됨')
  return page.url().split('/').at(-1) as string
}
