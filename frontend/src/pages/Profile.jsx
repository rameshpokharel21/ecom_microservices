import { useEffect, useState } from 'react'
import { useAuthContext } from 'react-oauth2-code-pkce'
import { getProfile, updateProfile } from '../api/users'
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

function Field({ label, name, value, onChange, disabled = false, hint }) {
  return (
    <label className="block">
      <span className="text-sm font-medium text-slate-700">{label}</span>
      <input
        name={name}
        value={value}
        onChange={onChange}
        disabled={disabled}
        readOnly={disabled}
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-indigo-500 disabled:bg-slate-100 disabled:text-slate-500"
      />
      {hint && <span className="mt-1 block text-xs text-slate-400">{hint}</span>}
    </label>
  )
}

export default function Profile() {
  const { tokenData } = useAuthContext()
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [editing, setEditing] = useState(false)
  const [form, setForm] = useState(null)
  const [saving, setSaving] = useState(false)

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

  const startEdit = () => {
    const a = profile?.address ?? {}
    setError(null)
    setForm({
      // Carried unchanged and sent straight back: PUT /api/users/me is a full REPLACE,
      // so any field left out of the payload is written back as null.
      firstName: profile?.firstName ?? '',
      lastName: profile?.lastName ?? '',
      email: profile?.email ?? '',
      phone: profile?.phone ?? '',
      // Coalesced field by field - a stored address may hold nulls, and a null in an
      // input value makes React treat the field as uncontrolled.
      address: {
        street: a.street ?? '',
        city: a.city ?? '',
        state: a.state ?? '',
        zipcode: a.zipcode ?? '',
        country: a.country ?? '',
      },
    })
    setEditing(true)
  }

  const onChange = (e) => setForm((f) => ({ ...f, [e.target.name]: e.target.value }))

  const onAddressChange = (e) =>
    setForm((f) => ({ ...f, address: { ...f.address, [e.target.name]: e.target.value } }))

  const onSubmit = async (e) => {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      // Same rule as signup: an all-blank address is dropped rather than stored as five
      // empty strings.
      const filled = Object.values(form.address).some((v) => v.trim() !== '')
      const { address, ...rest } = form
      // PUT returns the updated UserResponse, so there is nothing to refetch.
      setProfile(await updateProfile(filled ? form : rest))
      setEditing(false)
    } catch (err) {
      setError(readMessage(err))
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <Spinner label="Loading profile…" />
  if (!profile) return <ErrorBanner message={error} />

  const address = profile.address
  const addressLine = [address?.street, address?.city, address?.state, address?.zipcode, address?.country]
    .filter(Boolean)
    .join(', ')

  return (
    <div className="py-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-slate-900">Profile</h1>
        {!editing && (
          <button
            onClick={startEdit}
            className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Edit
          </button>
        )}
      </div>

      <ErrorBanner message={error} />

      {editing ? (
        <form onSubmit={onSubmit} className="mt-6 max-w-xl space-y-4 rounded-xl border border-slate-200 bg-white p-6">
          <div className="grid grid-cols-2 gap-4">
            <Field label="First name" name="firstName" value={form.firstName} disabled />
            <Field label="Last name" name="lastName" value={form.lastName} disabled />
          </div>
          {/* Locked on purpose. This endpoint writes the profile database only - it never
              calls the Keycloak Admin API - so an editable name or email would drift from
              the account that issues the token. Same reason the stored role was removed. */}
          <Field
            label="Email"
            name="email"
            value={form.email}
            disabled
            hint="Name and email are held by Keycloak. Change them in your account there, not here."
          />
          <Field label="Phone" name="phone" value={form.phone} onChange={onChange} />

          <fieldset className="space-y-4 rounded-lg border border-slate-200 p-4">
            <legend className="px-1 text-sm font-medium text-slate-700">
              Address <span className="font-normal text-slate-400">(optional)</span>
            </legend>
            <Field label="Street" name="street" value={form.address.street} onChange={onAddressChange} />
            <div className="grid grid-cols-2 gap-4">
              <Field label="City" name="city" value={form.address.city} onChange={onAddressChange} />
              <Field label="State" name="state" value={form.address.state} onChange={onAddressChange} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Zip code" name="zipcode" value={form.address.zipcode} onChange={onAddressChange} />
              <Field label="Country" name="country" value={form.address.country} onChange={onAddressChange} />
            </div>
          </fieldset>

          <div className="flex gap-3">
            <button
              type="submit"
              disabled={saving}
              className="rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:bg-slate-300"
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
            <button
              type="button"
              onClick={() => { setEditing(false); setError(null) }}
              className="rounded-lg border border-slate-300 px-5 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="mt-6 max-w-xl rounded-xl border border-slate-200 bg-white p-6">
          <Row label="Name" value={[profile.firstName, profile.lastName].filter(Boolean).join(' ')} />
          <Row label="Email" value={profile.email} />
          <Row label="Phone" value={profile.phone} />
          {/* From the token, not the profile. user-service no longer stores a role at all -
              Keycloak is the system of record and a stored copy could only go stale. */}
          <Row label="Roles" value={appRoles.join(', ')} />
          {/* No {address && ...} guard any more: Row renders an em dash for an empty value,
              and hiding the row made a missing address look absent rather than unset. */}
          <Row label="Address" value={addressLine} />
        </div>
      )}

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
