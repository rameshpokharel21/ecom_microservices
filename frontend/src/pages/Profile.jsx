import { useEffect, useState } from 'react'
import { useAuthContext } from 'react-oauth2-code-pkce'
import { getProfile } from '../api/users'
import { useAppRoles } from '../hooks/useRoles'
import { readMessage } from '../api/client'
import Spinner from '../components/Spinner'
import ErrorBanner from '../components/ErrorBanner'

function Row({ label, value }) {
  return (
    <div className="flex justify-between border-b border-slate-100 py-3 text-sm last:border-0">
      <span className="text-slate-500">{label}</span>
      <span className="font-medium text-slate-900">{value || '—'}</span>
    </div>
  )
}

export default function Profile() {
  const { tokenData } = useAuthContext()
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  // Still keyed on sub, but only to wait for a token and to refetch if the user changes -
  // the id is no longer sent. GET /api/users/me reads it from X-User-ID at the gateway.
  const sub = tokenData?.sub

  const appRoles = useAppRoles()

  useEffect(() => {
    if (!sub) return
    setLoading(true)
    getProfile()
      .then(setProfile)
      .catch((err) => setError(readMessage(err)))
      .finally(() => setLoading(false))
  }, [sub])

  if (loading) return <Spinner label="Loading profile…" />
  if (error) return <ErrorBanner message={error} />

  const address = profile?.addressDto

  return (
    <div className="py-8">
      <h1 className="text-2xl font-semibold text-slate-900">Profile</h1>

      <div className="mt-6 max-w-xl rounded-xl border border-slate-200 bg-white p-6">
        <Row label="Name" value={[profile?.firstName, profile?.lastName].filter(Boolean).join(' ')} />
        <Row label="Email" value={profile?.email} />
        <Row label="Phone" value={profile?.phone} />
        {/* From the token, not the profile. user-service no longer stores a role at all -
            Keycloak is the system of record and a stored copy could only go stale. */}
        <Row label="Roles" value={appRoles.join(', ')} />
        {address && (
          <Row
            label="Address"
            value={[address.street, address.city, address.state, address.zipCode, address.country]
              .filter(Boolean)
              .join(', ')}
          />
        )}
      </div>

      <details className="mt-6 max-w-xl rounded-xl border border-slate-200 bg-white p-4">
        <summary className="cursor-pointer text-sm font-medium text-slate-700">
          Access token claims
        </summary>
        <p className="mt-2 text-xs text-slate-500">
          The <code>sub</code> below is also this profile's Mongo <code>_id</code>, and is what
          the gateway injects downstream as <code>X-User-ID</code>.
        </p>
        <pre className="mt-3 max-h-72 overflow-auto rounded-lg bg-slate-900 p-4 text-xs text-slate-100">
          {JSON.stringify(tokenData, null, 2)}
        </pre>
      </details>
    </div>
  )
}
