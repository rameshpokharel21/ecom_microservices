import axios from 'axios'
import { API_BASE_URL } from '../config'

// ONE instance for the whole app, created at module load. The reference app called
// axios.create() inside a hook, which meant a new object identity on every render
// (unusable in a useEffect dependency array) and an Authorization header frozen at
// creation time, so it went stale the moment the token refreshed.
export const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
})

// The token lives in a module variable rather than in the instance's headers so the
// interceptor reads the CURRENT value at request time, not the value at create time.
let currentToken = null
export const setAuthToken = (token) => {
  currentToken = token
}

api.interceptors.request.use((config) => {
  if (currentToken) {
    config.headers.Authorization = `Bearer ${currentToken}`
  }
  // Never set X-User-ID. The gateway strips whatever arrives and rewrites it from the
  // token's "sub", so sending one is not a vulnerability - it is silently ignored,
  // which is worse, because it makes a forged header look like it worked.
  return config
})

// Handlers registered by ToastProvider/AuthSync, so this module stays free of React.
let onUnauthorized = null
let onError = null
export const setErrorHandlers = (handlers) => {
  onUnauthorized = handlers.onUnauthorized
  onError = handlers.onError
}

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status

    if (status === 401) {
      onUnauthorized?.()
    } else if (status === 429) {
      // The gateway rate-limits /api/products/** and /api/users/**. React StrictMode
      // double-invokes effects in dev, so every mount is two requests - this is the
      // usual cause in development, not a real flood.
      onError?.('Rate limited by the gateway. Wait a moment and retry.')
    } else if (status >= 500 || !error.response) {
      onError?.(readMessage(error) ?? 'The server is unreachable.')
    }
    return Promise.reject(error)
  },
)

// Two shapes, not one: order-service answers with { error, message }, user-service with
// an RFC 7807 ProblemDetail whose text lives in "detail". Missing that key is why a
// duplicate signup rendered as "Request failed with status code 409" instead of the
// reason. Falls back to axios's own text so nothing ever renders as "[object Object]".
export const readMessage = (error) =>
  error?.response?.data?.message ??
  error?.response?.data?.detail ??
  error?.response?.data?.error ??
  error?.message
