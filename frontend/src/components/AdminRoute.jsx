import { useAuthContext } from 'react-oauth2-code-pkce'
import { useIsAdmin } from '../hooks/useRoles'
import ProtectedRoute from './ProtectedRoute'

/*
 * A UX guard, NOT an access control. It hides screens whose calls would 403 anyway - the
 * gateway's hasRole("ADMIN") rules are the actual enforcement, checked against a signed
 * token on every request. Deleting this component would leak no data; deleting the
 * gateway rules would leak all of it.
 *
 * Wraps ProtectedRoute rather than repeating it, so an anonymous visitor gets "log in"
 * instead of "not authorised" - which is the truthful answer for someone with no token.
 */
export default function AdminRoute({ children }) {
  const { token } = useAuthContext()
  const isAdmin = useIsAdmin()

  if (token && !isAdmin) {
    return (
      <div className="py-20 text-center">
        <p className="text-4xl">🔒</p>
        <h2 className="mt-4 text-lg font-semibold text-slate-800">Admins only</h2>
        <p className="mx-auto mt-2 max-w-md text-sm text-slate-500">
          Your token carries no <code className="rounded bg-slate-100 px-1">ADMIN</code> realm
          role. It is assigned in the Keycloak console and only appears after a fresh login —
          roles are baked into the token when it is issued.
        </p>
      </div>
    )
  }

  return <ProtectedRoute>{children}</ProtectedRoute>
}
