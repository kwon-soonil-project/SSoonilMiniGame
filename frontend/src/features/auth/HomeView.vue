<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './authStore'

const auth = useAuthStore()
const router = useRouter()
const guestDialogOpen = ref(false)
const nickname = ref('')
const nicknameInput = ref<HTMLInputElement | null>(null)
const localError = ref<string | null>(null)

async function openGuestDialog(): Promise<void> {
  guestDialogOpen.value = true
  localError.value = null
  await nextTick()
  nicknameInput.value?.focus()
}

function closeGuestDialog(): void {
  if (!auth.loading) guestDialogOpen.value = false
}

async function submitGuest(): Promise<void> {
  localError.value = null
  try {
    await auth.joinAsGuest(nickname.value)
    await router.push('/lobby')
  } catch (cause) {
    localError.value = cause instanceof Error ? cause.message : '다시 시도해 주세요.'
  }
}
</script>

<template>
  <section class="home-shell" aria-labelledby="home-title">
    <div class="hero-copy">
      <p class="eyebrow">함께라서 더 재미있는 순간</p>
      <h1 id="home-title">링크 하나로 바로 시작하는<br><span>미니게임 놀이터</span></h1>
      <p>라이어 게임부터 그림 퀴즈까지, 친구들과 같은 방에 모여 즐겨보세요.</p>
      <div class="entry-actions">
        <button class="primary" data-action="open-guest" type="button" @click="openGuestDialog">
          게스트로 시작
        </button>
        <a class="google" href="/oauth2/authorization/google" rel="nofollow">Google로 계속</a>
      </div>
      <p class="entry-note">가입 없이 닉네임만으로 참가할 수 있어요.</p>
    </div>

    <div class="game-preview" aria-label="1차 제공 게임">
      <article><span aria-hidden="true">🕵️</span><strong>라이어 게임</strong></article>
      <article><span aria-hidden="true">🎨</span><strong>그림 퀴즈</strong></article>
      <article><span aria-hidden="true">ㅊㅅ</span><strong>초성 퀴즈</strong></article>
      <article><span aria-hidden="true">📊</span><strong>다수결 예측</strong></article>
    </div>

    <div
      v-if="guestDialogOpen"
      class="dialog-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby="guest-dialog-title"
      @keydown.esc="closeGuestDialog"
      @click.self="closeGuestDialog"
    >
      <form class="dialog-card" @submit.prevent="submitGuest">
        <button class="close-button" type="button" aria-label="게스트 창 닫기" @click="closeGuestDialog">×</button>
        <p class="eyebrow">빠른 입장</p>
        <h2 id="guest-dialog-title">게스트로 시작하기</h2>
        <label for="guest-nickname">닉네임</label>
        <input
          id="guest-nickname"
          ref="nicknameInput"
          v-model="nickname"
          maxlength="12"
          autocomplete="nickname"
          :aria-invalid="Boolean(localError)"
          :aria-describedby="localError ? 'guest-error' : 'guest-help'"
        >
        <p id="guest-help" class="field-help">2~12자, 방 안에서 보여줄 이름이에요.</p>
        <p v-if="localError" id="guest-error" class="field-error" role="alert">{{ localError }}</p>
        <button class="primary full" type="submit" :disabled="auth.loading">
          {{ auth.loading ? '입장 중…' : '로비로 입장' }}
        </button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.home-shell { min-height: calc(100vh - 5rem); display: grid; align-items: center; gap: 4rem; grid-template-columns: minmax(0, 1.1fr) minmax(20rem, .9fr); padding: clamp(3rem, 8vw, 7rem) max(1.25rem, calc((100vw - 1160px) / 2)); }
.hero-copy { max-width: 44rem; }
.eyebrow { color: #6652d9; font-size: .8rem; font-weight: 800; letter-spacing: .13em; text-transform: uppercase; }
h1 { margin: .6rem 0 1.25rem; font-size: clamp(2.4rem, 6vw, 5.2rem); line-height: 1.04; letter-spacing: -.055em; }
h1 span { color: #6652d9; }
.hero-copy > p:not(.eyebrow, .entry-note) { color: #5d6171; font-size: 1.08rem; line-height: 1.75; }
.entry-actions { display: flex; flex-wrap: wrap; gap: .75rem; margin-top: 2rem; }
.primary, .google { border: 0; border-radius: .9rem; cursor: pointer; font: inherit; font-weight: 800; padding: .9rem 1.25rem; text-align: center; text-decoration: none; }
.primary { background: #5b47d6; color: white; box-shadow: 0 10px 24px rgb(91 71 214 / 22%); }
.google { background: white; border: 1px solid #d9dbe5; color: #242735; }
.entry-note, .field-help { color: #777b8c; font-size: .85rem; }
.game-preview { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; transform: rotate(2deg); }
.game-preview article { aspect-ratio: 1.25; background: white; border: 1px solid #ecebf6; border-radius: 1.4rem; box-shadow: 0 18px 45px rgb(31 25 75 / 10%); display: grid; place-content: center; gap: .8rem; text-align: center; }
.game-preview article:nth-child(even) { transform: translateY(2rem); }
.game-preview span { font-size: 2.5rem; }
.dialog-backdrop { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 1rem; background: rgb(24 22 42 / 55%); }
.dialog-card { position: relative; width: min(100%, 26rem); border-radius: 1.25rem; background: white; box-shadow: 0 24px 70px rgb(22 16 64 / 30%); padding: 2rem; }
.dialog-card h2 { margin: .35rem 0 1.5rem; }
.dialog-card label { display: block; font-weight: 750; margin-bottom: .5rem; }
.dialog-card input { box-sizing: border-box; width: 100%; border: 1px solid #cfd2df; border-radius: .75rem; font: inherit; padding: .8rem; }
.close-button { position: absolute; right: 1rem; top: 1rem; border: 0; background: transparent; font-size: 1.6rem; cursor: pointer; }
.field-error { color: #b42318; font-size: .88rem; }
.full { width: 100%; margin-top: 1rem; }
@media (max-width: 767px) { .home-shell { grid-template-columns: 1fr; padding-top: 3rem; } .game-preview { order: -1; max-width: 26rem; transform: none; } .game-preview article { aspect-ratio: 1.7; } .game-preview article:nth-child(even) { transform: none; } }
</style>
