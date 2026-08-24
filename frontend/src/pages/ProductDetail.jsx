import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getProduct } from '../api/products'
import { readMessage } from '../api/client'
import { useCart } from '../context/CartContext'
import { useToast } from '../context/ToastContext'
import Spinner from '../components/Spinner'
import ErrorBanner from '../components/ErrorBanner'

export default function ProductDetail() {
  const { id } = useParams()
  const { addItem } = useCart()
  const { showToast } = useToast()

  const [product, setProduct] = useState(null)
  const [quantity, setQuantity] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    getProduct(id)
      .then((data) => !cancelled && setProduct(data))
      .catch((err) => !cancelled && setError(readMessage(err)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [id])

  const handleAdd = async () => {
    setAdding(true)
    try {
      await addItem(product.id, quantity)
      showToast(`${quantity} × ${product.name} added`, 'info')
    } catch (err) {
      showToast(readMessage(err))
    } finally {
      setAdding(false)
    }
  }

  if (loading) return <Spinner />
  if (error) return <ErrorBanner message={error} />
  if (!product) return null

  const outOfStock = (product.stockQuantity ?? 0) <= 0

  return (
    <div className="py-8">
      <Link to="/" className="text-sm text-slate-500 hover:text-slate-800">
        ← Back to catalogue
      </Link>

      <div className="mt-6 grid gap-8 md:grid-cols-2">
        <div className="flex h-72 items-center justify-center rounded-xl bg-slate-100">
          {product.imageUrl ? (
            <img src={product.imageUrl} alt={product.name} className="h-full w-full rounded-xl object-cover" />
          ) : (
            <span className="text-6xl text-slate-300">📦</span>
          )}
        </div>

        <div>
          <p className="text-xs uppercase tracking-wide text-indigo-600">{product.category}</p>
          <h1 className="mt-1 text-3xl font-semibold text-slate-900">{product.name}</h1>
          <p className="mt-3 text-slate-600">{product.description}</p>

          <p className="mt-6 text-3xl font-semibold text-slate-900">
            ${Number(product.price).toFixed(2)}
          </p>
          <p className={`mt-1 text-sm ${outOfStock ? 'text-red-600' : 'text-slate-500'}`}>
            {outOfStock ? 'Out of stock' : `${product.stockQuantity} in stock`}
          </p>

          <div className="mt-6 flex items-center gap-3">
            <input
              type="number"
              min="1"
              max={product.stockQuantity || 1}
              value={quantity}
              onChange={(e) => setQuantity(Math.max(1, Number(e.target.value)))}
              className="w-20 rounded-lg border border-slate-300 px-3 py-2 text-sm"
            />
            <button
              onClick={handleAdd}
              disabled={outOfStock || adding}
              className="rounded-lg bg-indigo-600 px-6 py-2.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:bg-slate-300"
            >
              {adding ? 'Adding…' : 'Add to cart'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
