import { useCallback, useEffect, useState } from 'react'
import { createProduct, deleteProduct, listProducts, updateProduct } from '../api/products'
import { readMessage } from '../api/client'
import { useToast } from '../context/ToastContext'
import Spinner from '../components/Spinner'
import ErrorBanner from '../components/ErrorBanner'

// Mirrors ProductRequest exactly. Missing a field here sends null and silently wipes it
// on update, because PUT replaces the whole representation rather than patching it.
const EMPTY = {
  name: '',
  description: '',
  price: '',
  stockQuantity: '',
  category: '',
  imageUrl: '',
}

function Field({ label, name, value, onChange, type = 'text', ...rest }) {
  return (
    <label className="block">
      <span className="text-xs font-medium text-slate-600">{label}</span>
      <input
        name={name}
        type={type}
        value={value ?? ''}
        onChange={onChange}
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-indigo-500"
        {...rest}
      />
    </label>
  )
}

export default function AdminProducts() {
  const { showToast } = useToast()
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [form, setForm] = useState(EMPTY)
  const [editingId, setEditingId] = useState(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    listProducts()
      .then(setProducts)
      .catch((err) => setError(readMessage(err)))
      .finally(() => setLoading(false))
  }, [])

  useEffect(load, [load])

  const onChange = (e) => setForm((f) => ({ ...f, [e.target.name]: e.target.value }))

  const reset = () => {
    setForm(EMPTY)
    setEditingId(null)
  }

  const startEdit = (p) => {
    setEditingId(p.id)
    setForm({
      name: p.name ?? '',
      description: p.description ?? '',
      // Inputs hold strings; price is a BigDecimal and stockQuantity an Integer server
      // side, so both are converted back on submit rather than sent as text.
      price: p.price ?? '',
      stockQuantity: p.stockQuantity ?? '',
      category: p.category ?? '',
      imageUrl: p.imageUrl ?? '',
    })
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const onSubmit = async (e) => {
    e.preventDefault()
    setSaving(true)
    const payload = {
      ...form,
      price: form.price === '' ? null : Number(form.price),
      stockQuantity: form.stockQuantity === '' ? null : Number(form.stockQuantity),
      imageUrl: form.imageUrl || null,
    }
    try {
      if (editingId == null) {
        await createProduct(payload)
        showToast(`Created ${payload.name}`, 'info')
      } else {
        await updateProduct(editingId, payload)
        showToast(`Updated ${payload.name}`, 'info')
      }
      reset()
      load()
    } catch (err) {
      showToast(readMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const onDelete = async (p) => {
    if (!window.confirm(`Delete "${p.name}"?`)) return
    try {
      await deleteProduct(p.id)
      showToast(`Deleted ${p.name}`, 'info')
      // Deleting a product does NOT clear it from anyone's cart - cart rows only hold a
      // productId, and the lookup that would fail happens at checkout, not here.
      if (editingId === p.id) reset()
      load()
    } catch (err) {
      showToast(readMessage(err))
    }
  }

  return (
    <div className="py-8">
      <h1 className="text-2xl font-semibold text-slate-900">Products</h1>

      <form
        onSubmit={onSubmit}
        className="mt-6 rounded-xl border border-slate-200 bg-white p-6"
      >
        <h2 className="text-sm font-semibold text-slate-800">
          {editingId == null ? 'Add a product' : `Editing #${editingId}`}
        </h2>

        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <Field label="Name" name="name" value={form.name} onChange={onChange} required />
          <Field label="Category" name="category" value={form.category} onChange={onChange} />
          <Field
            label="Price"
            name="price"
            type="number"
            step="0.01"
            min="0"
            value={form.price}
            onChange={onChange}
            required
          />
          <Field
            label="Stock quantity"
            name="stockQuantity"
            type="number"
            min="0"
            value={form.stockQuantity}
            onChange={onChange}
            required
          />
          <div className="sm:col-span-2">
            <Field label="Image URL" name="imageUrl" value={form.imageUrl} onChange={onChange} />
          </div>
          <div className="sm:col-span-2">
            <label className="block">
              <span className="text-xs font-medium text-slate-600">Description</span>
              <textarea
                name="description"
                value={form.description}
                onChange={onChange}
                rows={2}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-indigo-500"
              />
            </label>
          </div>
        </div>

        <div className="mt-5 flex items-center gap-3">
          <button
            type="submit"
            disabled={saving}
            className="rounded-lg bg-indigo-600 px-5 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:bg-slate-300"
          >
            {saving ? 'Saving…' : editingId == null ? 'Create' : 'Save changes'}
          </button>
          {editingId != null && (
            <button
              type="button"
              onClick={reset}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
          )}
        </div>
      </form>

      <ErrorBanner message={error} />

      {loading ? (
        <Spinner label="Loading products…" />
      ) : (
        <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3 font-medium">Id</th>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Category</th>
                <th className="px-4 py-3 font-medium">Price</th>
                <th className="px-4 py-3 font-medium">Stock</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {products.map((p) => (
                <tr key={p.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-3 font-mono text-xs text-slate-400">{p.id}</td>
                  <td className="px-4 py-3 text-slate-900">{p.name}</td>
                  <td className="px-4 py-3 text-slate-600">{p.category || '—'}</td>
                  <td className="px-4 py-3 text-slate-600">{p.price}</td>
                  <td className="px-4 py-3 text-slate-600">{p.stockQuantity}</td>
                  <td className="px-4 py-3 text-right">
                    <button
                      onClick={() => startEdit(p)}
                      className="mr-2 rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
                    >
                      Edit
                    </button>
                    <button
                      onClick={() => onDelete(p)}
                      className="rounded-lg border border-red-200 px-3 py-1.5 text-xs font-medium text-red-600 hover:bg-red-50"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
