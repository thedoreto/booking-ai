// Заявките на админ панела към booking-ai (/api/admin/**, същият адрес като страницата).
// Токенът е в sessionStorage – изчезва, когато се затвори табът.

const TOKEN_KEY = 'adminToken'

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function saveToken(token) {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY)
}

// Грешка с кода от booking-ai (INVALID_CREDENTIALS, TOO_MANY_ATTEMPTS, UNAUTHORIZED) или NETWORK
export class ApiError extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

async function request(path, options = {}) {
  let response
  try {
    response = await fetch(`/api/admin${path}`, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options.headers },
    })
  } catch {
    throw new ApiError('NETWORK')
  }
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new ApiError(body.error || 'NETWORK')
  }
  return body
}

export function login(hotelId, email, password) {
  return request('/login', { method: 'POST', body: JSON.stringify({ hotelId, email, password }) })
}

export function me(token) {
  return request('/me', { headers: { Authorization: `Bearer ${token}` } })
}
