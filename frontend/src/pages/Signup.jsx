import { useState } from 'react'
import { useAuthContext } from 'react-oauth2-code-pkce'
import { signup } from '../api/users'
import { readMessage } from '../api/client'
import ErrorBanner from '../components/ErrorBanner'

const EMPTY_ADDRESS = { street: '', city: '', state: '', zipcode: '', country: '' }

const EMPTY = {
  username: '',
  password: '',
  email: '',
  firstName: '',
  lastName: '',
  phone: '',
  // Nested under "address" to match UserRequest.address. Note "zipcode" is all
  // lower-case on the Java side; zipCode silently maps to nothing.
  address: { ...EMPTY_ADDRESS },
}

function Field({ label, name, value, onChange, type = 'text', required = true }) {
  return (
    <label className="block">
      <span className="text-sm font-medium text-slate-700">{label}</span>
      <input
        name={name}
        type={type}
        value={value}
        onChange={onChange}
        required={required}
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-indigo-500"
      />
    </label>
  )
}

export default function Signup() {
  const { login } = useAuthContext()
  const [form, setForm] = useState(EMPTY)
  const [error, setError] = useState(null)
  const [created, setCreated] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const onChange = (e) => setForm((f) => ({ ...f, [e.target.name]: e.target.value }))

  const onAddressChange = (e) =>
    setForm((f) => ({ ...f, address: { ...f.address, [e.target.name]: e.target.value } }))

  const onSubmit = async (e) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      // The address is optional. Send it only if something was typed - otherwise drop
      // the key entirely so the profile stores null rather than five empty strings.
      const filled = Object.values(form.address).some((v) => v.trim() !== '')
      const { address, ...rest } = form
      setCreated(await signup(filled ? form : rest))
    } catch (err) {
      // user-service forwards Keycloak's own message, so a duplicate username reads as
      // "User exists with same username" rather than a bare 409.
      setError(readMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  /*
   * Signup does NOT log you in, and cannot. POST /api/users provisions the account
   * through Keycloak's Admin API using the ecom-admin SERVICE ACCOUNT - a token that
   * authorizes administration, not one that represents the new human. Minting a user
   * token server-side would mean enabling the password grant and handling the raw
   * password in the API, which is the exact thing PKCE exists to avoid. So the account
   * is created here and the password is typed once more, at Keycloak, where it belongs.
   */
  if (created) {
    return (
      <div className="mx-auto max-w-md py-16 text-center">
        <p className="text-4xl">✅</p>
        <h1 className="mt-4 text-xl font-semibold text-slate-900">Account created</h1>
        <p className="mt-2 text-sm text-slate-500">
          {created.email} is registered as a customer. Signing up does not sign you in —
          log in to get a token.
        </p>
        <p className="mt-4 break-all rounded-lg bg-slate-100 p-3 text-xs text-slate-600">
          id: {created.id}
        </p>
        <button
          onClick={() => login()}
          className="mt-6 rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-700"
        >
          Log in
        </button>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-md py-10">
      <h1 className="text-2xl font-semibold text-slate-900">Create an account</h1>
      <p className="mt-1 text-sm text-slate-500">
        This is the only endpoint the gateway leaves open — no token required.
      </p>

      <ErrorBanner message={error} />

      <form onSubmit={onSubmit} className="mt-6 space-y-4 rounded-xl border border-slate-200 bg-white p-6">
        <Field label="Username" name="username" value={form.username} onChange={onChange} />
        <Field label="Email" name="email" type="email" value={form.email} onChange={onChange} />
        <Field label="Password" name="password" type="password" value={form.password} onChange={onChange} />
        <div className="grid grid-cols-2 gap-4">
          <Field label="First name" name="firstName" value={form.firstName} onChange={onChange} />
          <Field label="Last name" name="lastName" value={form.lastName} onChange={onChange} />
        </div>
        <Field label="Phone" name="phone" value={form.phone} onChange={onChange} required={false} />

        <fieldset className="space-y-4 rounded-lg border border-slate-200 p-4">
          <legend className="px-1 text-sm font-medium text-slate-700">
            Address <span className="font-normal text-slate-400">(optional)</span>
          </legend>
          <Field label="Street" name="street" value={form.address.street} onChange={onAddressChange} required={false} />
          <div className="grid grid-cols-2 gap-4">
            <Field label="City" name="city" value={form.address.city} onChange={onAddressChange} required={false} />
            <Field label="State" name="state" value={form.address.state} onChange={onAddressChange} required={false} />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Zip code" name="zipcode" value={form.address.zipcode} onChange={onAddressChange} required={false} />
            <Field label="Country" name="country" value={form.address.country} onChange={onAddressChange} required={false} />
          </div>
        </fieldset>

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:bg-slate-300"
        >
          {submitting ? 'Creating…' : 'Sign up'}
        </button>
      </form>
    </div>
  )
}
