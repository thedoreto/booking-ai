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

// Грешка с кода от booking-ai (INVALID_CREDENTIALS, TOO_MANY_ATTEMPTS, UNAUTHORIZED, TEXT_REQUIRED…) или NETWORK
// body – целият отговор (напр. usedBy при IN_USE)
export class ApiError extends Error {
  constructor(code, body = {}) {
    super(code)
    this.code = code
    this.body = body
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
    throw new ApiError(body.error || 'NETWORK', body)
  }
  return body
}

export function login(hotelId, email, password) {
  return request('/login', { method: 'POST', body: JSON.stringify({ hotelId, email, password }) })
}

function authorized(token) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

export function me(token) {
  return request('/me', authorized(token))
}

// Знанията на хотела: [{ id, title, category, tags, source, text }]
export function knowledge(token) {
  return request('/knowledge', authorized(token))
}

// Промяна на знание: { title, category, tags, source, text } → новият документ
export function updateKnowledge(token, id, changes) {
  return request(`/knowledge/${encodeURIComponent(id)}`, {
    ...authorized(token),
    method: 'PUT',
    body: JSON.stringify(changes),
  })
}

// Ново знание → създаденият документ
export function createKnowledge(token, changes) {
  return request('/knowledge', { ...authorized(token), method: 'POST', body: JSON.stringify(changes) })
}

// Изтриване; знание, което се ползва от бутон, не се трие (грешка IN_USE)
export function deleteKnowledge(token, id) {
  return request(`/knowledge/${encodeURIComponent(id)}`, { ...authorized(token), method: 'DELETE' })
}

// Езиците на хотела: { languages: [{ code, name }], defaultLanguage }
export function settings(token) {
  return request('/settings', authorized(token))
}

// Предложение за превод на един език от Gemini – не се записва: { language, text }
export function suggestTranslation(token, id, language) {
  return request(`/knowledge/${encodeURIComponent(id)}/translations/${encodeURIComponent(language)}/suggest`, {
    ...authorized(token),
    method: 'POST',
  })
}

// Записва превода на един език → документът
export function saveTranslation(token, id, language, text) {
  return request(`/knowledge/${encodeURIComponent(id)}/translations/${encodeURIComponent(language)}`, {
    ...authorized(token),
    method: 'PUT',
    body: JSON.stringify({ text }),
  })
}

// Gemini превежда основния текст на всички езици на хотела и ги записва → документът
export function translateAll(token, id) {
  return request(`/knowledge/${encodeURIComponent(id)}/translate-all`, { ...authorized(token), method: 'POST' })
}

// Бутоните на хотела, и неактивните, в реда в чата:
// [{ shortcutId, label: { bg, en }, category, isActive, guestVisible, action: { type, tool, knowledgeIds }, order }]
export function shortcuts(token) {
  return request('/shortcuts', authorized(token))
}

// Tool-овете, които бутон може да пусне: [{ name, description }]
export function shortcutTools(token) {
  return request('/shortcuts/tools', authorized(token))
}

// Нов бутон { shortcutId, label, category, isActive, guestVisible, action } → създаденият (отива последен)
export function createShortcut(token, changes) {
  return request('/shortcuts', { ...authorized(token), method: 'POST', body: JSON.stringify(changes) })
}

// Промяна на бутон (shortcutId не се сменя) → новият бутон
export function updateShortcut(token, shortcutId, changes) {
  return request(`/shortcuts/${encodeURIComponent(shortcutId)}`, {
    ...authorized(token),
    method: 'PUT',
    body: JSON.stringify(changes),
  })
}

export function deleteShortcut(token, shortcutId) {
  return request(`/shortcuts/${encodeURIComponent(shortcutId)}`, { ...authorized(token), method: 'DELETE' })
}

// Новият ред в чата – всички shortcutId → бутоните в новия ред
export function reorderShortcuts(token, shortcutIds) {
  return request('/shortcuts/order', { ...authorized(token), method: 'PUT', body: JSON.stringify({ shortcutIds }) })
}

// Отчетите за последните days дни (1..180): { days, summary, funnel, noRooms, buttons }
export function reports(token, days) {
  return request(`/reports?days=${encodeURIComponent(days)}`, authorized(token))
}

// Заявките и токените към Gemini за последните days дни (без цена)
export function geminiReport(token, days) {
  return request(`/reports/gemini?days=${encodeURIComponent(days)}`, authorized(token))
}
