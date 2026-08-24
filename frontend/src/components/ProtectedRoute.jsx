import { useAuthContext } from 'react-oauth2-code-pkce'

export default function ProtectedRoute({ children }) {
  const { token, loginInProgress, login } = useAuthContext()

  if (loginInProgress) {
    return <p className="py-16 text-center text-slate-500">Signing you in…</p>
  }

  // Deliberately a prompt rather than an automatic login() redirect: bouncing straight
  // to Keycloak from a deep link makes an expired session indistinguishable from a bug.
  if (!token) {
    return (
      <div className="py-20 text-center">
        <h2 className="text-lg font-semibold text-slate-800">Sign in to continue</h2>
        <p className="mt-1 text-sm text-slate-500">This page needs an account.</p>
        <button
          onClick={() => login()}
          className="mt-5 rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-700"
        >
          Log in
        </button>
      </div>
    )
  }

  return children
}
