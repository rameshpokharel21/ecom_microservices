import { useCallback, useEffect, useState } from 'react'
import { useAuthContext } from 'react-oauth2-code-pkce'
import { listProducts, searchProducts } from '../api/products'
import { readMessage } from '../api/client'
import { useCart } from '../context/CartContext'
import { useToast } from '../context/ToastContext'
import ProductCard from '../components/ProductCard'
import Spinner from '../components/Spinner'
import ErrorBanner from '../components/ErrorBanner'

export default function Catalogue() {
  const { token, login } = useAuthContext()
  const { addItem } = useCart()
  const { showToast } = useToast()

  const [products, setProducts] = useState([])
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [addingId, setAddingId] = useState(null)

  const load = useCallback(async (term) => {
    setLoading(true)
    setError(null)
    try {
      const data = term?.trim() ? await searchProducts(term.trim()) : await listProducts()
      setProducts(data)
    } catch (err) {
      setError(readMessage(err))
    } finally {
      setLoading(false)
    }
  }, [])

  // /api/products requires a token like everything else, so this reruns after login.
  useEffect(() => {
    if (token) load()
    else setLoading(false)
  }, [token, load])

  const handleAdd = async (product) => {
    setAddingId(product.id)
    try {
      // product.id is a Long; CartItemRequest.productId is a String. addItem stringifies.
      await addItem(product.id, 1)
      showToast(`${product.name} added to cart`, 'info')
    } catch (err) {
      showToast(readMessage(err))
    } finally {
      setAddingId(null)
    }
  }

  if (!token) {
    return (
      <div className="py-20 text-center">
        <h1 className="text-2xl font-semibold text-slate-900">Welcome</h1>
        <p className="mt-2 text-sm text-slate-500">
          The catalogue is behind the gateway — every route but signup needs a token.
        </p>
        <button
          onClick={() => login()}
          className="mt-6 rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-700"
        >
          Log in to browse
        </button>
      </div>
    )
  }

  return (
    <div className="py-8">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold text-slate-900">Catalogue</h1>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            load(keyword)
          }}
          className="flex gap-2"
        >
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="Search products…"
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-indigo-500"
          />
          <button
            type="submit"
            className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700"
          >
            Search
          </button>
          {keyword && (
            <button
              type="button"
              onClick={() => {
                setKeyword('')
                load()
              }}
              className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-600 hover:bg-slate-50"
            >
              Clear
            </button>
          )}
        </form>
      </div>

      <ErrorBanner message={error} onRetry={() => load(keyword)} />

      {loading ? (
        <Spinner label="Loading products…" />
      ) : products.length === 0 ? (
        <p className="py-16 text-center text-slate-500">No products found.</p>
      ) : (
        <div className="mt-6 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {products.map((product) => (
            <ProductCard
              key={product.id}
              product={product}
              onAdd={handleAdd}
              adding={addingId === product.id}
            />
          ))}
        </div>
      )}
    </div>
  )
}
