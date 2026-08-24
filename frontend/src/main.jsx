import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AuthProvider, useAuthContext } from 'react-oauth2-code-pkce'

import './index.css'
import App from './App.jsx'
import { authConfig } from './authConfig.js'
import { setAuthToken, setErrorHandlers } from './api/client.js'
import { ToastProvider, useToast } from './context/ToastContext.jsx'
import { CartProvider } from './context/CartContext.jsx'

/**
 * Bridges the auth context into the plain-JS axios module, so no component ever has to
 * pass a token to an API call.
 *
 * setAuthToken runs during RENDER rather than in an effect, and that is deliberate:
 * React runs effects child-first, so CartProvider's first fetch would fire before a
 * parent's effect could install the token - and land as a 401 on every page load.
 * Rendering is top-down, so assigning here happens before any child renders or fetches.
 * The call is idempotent, which is what makes it safe to do during render.
 */
function AuthSync({ children }) {
  const { token, logOut } = useAuthContext()
  const { showToast } = useToast()

  setAuthToken(token)

  useEffect(() => {
    setErrorHandlers({
      // A 401 here means the token was rejected by the gateway, not merely absent -
      // expired, or issued by an issuer it does not trust. Dropping local state puts
      // the UI back in its logged-out shape instead of looping on failing calls.
      onUnauthorized: () => {
        showToast('Session expired. Please log in again.')
        logOut()
      },
      onError: showToast,
    })
  }, [logOut, showToast])

  return children
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <AuthProvider
      authConfig={authConfig}
      loadingComponent={<p className="py-20 text-center text-slate-500">Signing you in…</p>}
    >
      <ToastProvider>
        <AuthSync>
          <CartProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </CartProvider>
        </AuthSync>
      </ToastProvider>
    </AuthProvider>
  </StrictMode>,
)
