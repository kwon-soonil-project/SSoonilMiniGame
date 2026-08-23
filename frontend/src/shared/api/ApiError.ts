interface ApiErrorBody {
  code?: unknown
  message?: unknown
  requestId?: unknown
}

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly requestId: string,
    readonly status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  static async fromResponse(response: Response): Promise<ApiError> {
    let body: ApiErrorBody = {}
    try {
      body = await response.json() as ApiErrorBody
    } catch {
      // A stable local fallback keeps transport failures displayable.
    }
    return new ApiError(
      typeof body.code === 'string' ? body.code : 'HTTP_ERROR',
      typeof body.message === 'string' ? body.message : '요청을 처리하지 못했습니다.',
      typeof body.requestId === 'string' ? body.requestId : '',
      response.status,
    )
  }
}
