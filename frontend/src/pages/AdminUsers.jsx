import { useCallback, useEffect, useState } from 'react'
import { useAuthContext } from 'react-oauth2-code-pkce'
import { deleteUser, listUsers } from '../api/users'
import { readMessage } from '../api/client'
import { useToast } from '../context/ToastContext'
import Spinner from '../components/Spinner'
import ErrorBanner from '../components/ErrorBanner'

export default function AdminUsers() {
  const { tokenData } = useAuthContext()
  const { showToast } = useToast()
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [deleting, setDeleting] = useState(null)

  const load = useCallback(() => {
    setLoading(true)
    listUsers()
      .then(setUsers)
      .catch((err) => setError(readMessage(err)))
      .finally(() => setLoading(false))
  }, [])

  useEffect(load, [load])

  const onDelete = async (user) => {
    // Deletes the Keycloak account too, so it is not reversible from this screen - and
    // the profile row is the only place the id is visible once it is gone.
    if (!window.confirm(`Delete ${user.email}? This removes the Keycloak account too.`)) return
    setDeleting(user.id)
    try {
      await deleteUser(user.id)
      showToast(`Deleted ${user.email}`)
      load()
    } catch (err) {
      showToast(readMessage(err))
    } finally {
      setDeleting(null)
    }
  }

  if (loading) return <Spinner label="Loading users…" />
  if (error) return <ErrorBanner message={error} />

  return (
    <div className="py-8">
      <h1 className="text-2xl font-semibold text-slate-900">Users</h1>
      <p className="mt-1 text-sm text-slate-500">
        {users.length} account{users.length === 1 ? '' : 's'}. Roles are not shown here —
        they live in Keycloak, not in the profile record, so this list cannot report them.
      </p>

      <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Name</th>
              <th className="px-4 py-3 font-medium">Email</th>
              <th className="px-4 py-3 font-medium">Phone</th>
              <th className="px-4 py-3 font-medium">Id (Keycloak sub)</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {users.map((u) => {
              const isSelf = u.id === tokenData?.sub
              return (
                <tr key={u.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-3 text-slate-900">
                    {[u.firstName, u.lastName].filter(Boolean).join(' ') || '—'}
                    {isSelf && (
                      <span className="ml-2 rounded bg-indigo-50 px-1.5 py-0.5 text-[10px] font-medium text-indigo-600">
                        you
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-slate-600">{u.email}</td>
                  <td className="px-4 py-3 text-slate-600">{u.phone || '—'}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-400">{u.id}</td>
                  <td className="px-4 py-3 text-right">
                    <button
                      onClick={() => onDelete(u)}
                      // Deleting yourself would leave a live token whose /me is gone, and
                      // if you are the only admin nobody can reach this screen again.
                      disabled={isSelf || deleting === u.id}
                      title={isSelf ? 'You cannot delete your own account here' : undefined}
                      className="rounded-lg border border-red-200 px-3 py-1.5 text-xs font-medium text-red-600 hover:bg-red-50 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                    >
                      {deleting === u.id ? 'Deleting…' : 'Delete'}
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
