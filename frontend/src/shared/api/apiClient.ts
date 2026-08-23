import { ApiError } from './ApiError'

interface CsrfResponse {
  headerName: string
  parameterName: string
  token: string
}

export interface ApiRequestOptions {
  csrf?: boolean
}

const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
let csrfToken: CsrfResponse | null = null

export function invalidateCsrfToken(): void {
  csrfToken = null
}

async function fetchCsrfToken(): Promise<CsrfResponse> {
  const response = await fetch('/api/v1/csrf', { credentials: 'include' })
  if (!response.ok) throw await ApiError.fromResponse(response)
  const token = await response.json() as CsrfResponse
  if (!token.headerName || !token.token) {
    throw new ApiError('CSRF_CONTRACT_INVALID', '보안 토큰을 받지 못했습니다.', '', response.status)
  }
  csrfToken = token
  return token
}

function requestId(): string {
  return crypto.randomUUID()
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
  options: ApiRequestOptions = {},
): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const requiresCsrf = unsafeMethods.has(method) && options.csrf !== false
  const headers = new Headers(init.headers)
  if (init.body != null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (unsafeMethods.has(method) && !headers.has('X-Request-Id')) {
    headers.set('X-Request-Id', requestId())
  }

  const execute = async (refreshCsrf: boolean): Promise<Response> => {
    if (requiresCsrf) {
      const token = refreshCsrf || csrfToken === null ? await fetchCsrfToken() : csrfToken
      headers.set(token.headerName, token.token)
    }
    return fetch(path, { ...init, method, credentials: 'include', headers })
  }

  let response = await execute(false)
  if (requiresCsrf && response.status === 403) {
    invalidateCsrfToken()
    response = await execute(true)
  }
  if (!response.ok) throw await ApiError.fromResponse(response)
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}
