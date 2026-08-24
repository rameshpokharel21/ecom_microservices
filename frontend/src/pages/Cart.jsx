import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useCart } from '../context/CartContext'
import { useToast } from '../context/ToastContext'
import { placeOrder } from '../api/orders'
import { readMessage } from '../api/client'
import Spinner from '../components/Spinner'

export default function Cart() {
  const { items, total, loading, removeItem, refresh } = useCart()
  const { showToast } = useToast()
  const navigate = useNavigate()
  const [placing, setPlacing] = useState(false)

  const handleCheckout = async () => {
    setPlacing(true)
    try {
      const order = await placeOrder()
      // The cart is cleared server-side by OrderService, so the local copy must be
      // re-read rather than assumed empty.
      await refresh()
      showToast(`Order #${order.id} confirmed`, 'info')
      navigate('/orders')
    } catch (err) {
      showToast(readMessage(err))
    } finally {
      setPlacing(false)
    }
  }

  if (loading && items.length === 0) return <Spinner label="Loading cart…" />

  if (items.length === 0) {
    return (
      <div className="py-20 text-center">
        <p className="text-4xl">🛒</p>
        <h1 className="mt-4 text-xl font-semibold text-slate-900">Your cart is empty</h1>
        <Link
          to="/"
          className="mt-6 inline-block rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-700"
        >
          Browse the catalogue
        </Link>
      </div>
    )
  }

  return (
    <div className="py-8">
      <h1 className="text-2xl font-semibold text-slate-900">Your cart</h1>

      <div className="mt-6 overflow-hidden rounded-xl border border-slate-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-5 py-3">Product</th>
              <th className="px-5 py-3">Unit price</th>
              <th className="px-5 py-3">Qty</th>
              <th className="px-5 py-3">Subtotal</th>
              <th className="px-5 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((item) => (
              <tr key={item.productId}>
                <td className="px-5 py-4">
                  <Link
                    to={`/products/${item.productId}`}
                    className="font-medium text-slate-800 hover:text-indigo-600"
                  >
                    Product #{item.productId}
                  </Link>
                </td>
                <td className="px-5 py-4 text-slate-600">${Number(item.price).toFixed(2)}</td>
                <td className="px-5 py-4 text-slate-600">{item.quantity}</td>
                <td className="px-5 py-4 font-medium text-slate-900">
                  ${Number(item.subTotal).toFixed(2)}
                </td>
                <td className="px-5 py-4 text-right">
                  <button
                    onClick={() => removeItem(item.productId).catch((e) => showToast(readMessage(e)))}
                    className="text-xs font-medium text-red-600 hover:text-red-800"
                  >
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-6 flex items-center justify-end gap-6">
        <span className="text-sm text-slate-500">Total</span>
        <span className="text-2xl font-semibold text-slate-900">${total.toFixed(2)}</span>
        <button
          onClick={handleCheckout}
          disabled={placing}
          className="rounded-lg bg-emerald-600 px-6 py-2.5 text-sm font-medium text-white hover:bg-emerald-700 disabled:bg-slate-300"
        >
          {placing ? 'Placing order…' : 'Place order'}
        </button>
      </div>
    </div>
  )
}
