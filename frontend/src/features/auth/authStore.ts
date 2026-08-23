import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiError } from '../../shared/api/ApiError'
import { apiRequest, invalidateCsrfToken } from '../../shared/api/apiClient'

export interface Actor {
  actorId: string
  actorType: 'GUEST' | 'MEMBER'
  nickname: string
  memberId: string | null
}

export const useAuthStore = defineStore('auth', () => {
  const actor = ref<Actor | null>(null)
  const initialized = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let initialization: Promise<void> | null = null

  function initialize(): Promise<void> {
    if (initialized.value) return Promise.resolve()
    if (initialization) return initialization
    initialization = initializeOnce().finally(() => {
      initialization = null
    })
    return initialization
  }

  async function initializeOnce(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      actor.value = await apiRequest<Actor>('/api/v1/me')
      initialized.value = true
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) {
        actor.value = null
        initialized.value = true
      } else {
        error.value = cause instanceof Error ? cause.message : '로그인 상태를 확인하지 못했습니다.'
        throw cause
      }
    } finally {
      loading.value = false
    }
  }

  async function retryInitialize(): Promise<void> {
    error.value = null
    await initialize()
  }

  async function joinAsGuest(rawNickname: string): Promise<Actor> {
    const nickname = rawNickname.trim()
    const length = Array.from(nickname).length
    if (length < 2 || length > 12) {
      const validationError = '닉네임은 2~12자로 입력해 주세요.'
      error.value = validationError
      throw new Error(validationError)
    }
    loading.value = true
    error.value = null
    try {
      const created = await apiRequest<Actor>('/api/v1/auth/guest', {
        method: 'POST',
        body: JSON.stringify({ nickname }),
      }, { csrf: false })
      actor.value = created
      initialized.value = true
      invalidateCsrfToken()
      return created
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '게스트로 시작하지 못했습니다.'
      throw cause
    } finally {
      loading.value = false
    }
  }

  return { actor, initialized, loading, error, initialize, retryInitialize, joinAsGuest }
})
