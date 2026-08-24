import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listOrders } from '../api/orders'
import { readMessage } from '../api/client'
import Spinner from '../components/Spinner'
import ErrorBanner from '../components/ErrorBanner'

const statusStyle = {
  CONFIRMED: 'bg-emerald-100 text-emerald-800',
  PENDING: 'bg-amber-100 text-amber-800',
  CANCELLED: 'bg-red-100 text-red-800',
}

export default function Orders() {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const load = () => {
    setLoading(true)
    setError(null)
    listOrders()
      .then(setOrders)
      .catch((err) => setError(readMessage(err)))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  if (loading) return <Spinner label="Loading orders…" />
  if (error) return <ErrorBanner message={error} onRetry={load} />

  // GET /api/orders answers 200 [] for a user who has never ordered - an empty history
  // is not an error, so this is a normal state and not an ErrorBanner.
  if (orders.length === 0) {
    return (
      <div className="py-20 text-center">
        <p className="text-4xl">📦</p>
        <h1 className="mt-4 text-xl font-semibold text-slate-900">No orders yet</h1>
        <Link
          to="/"
          className="mt-6 inline-block rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-700"
        >
          Start shopping
        </Link>
      </div>
    )
  }

  return (
    <div className="py-8">
      <h1 className="text-2xl font-semibold text-slate-900">Your orders</h1>

      <div className="mt-6 space-y-4">
        {orders.map((order) => (
          <div key={order.id} className="rounded-xl border border-slate-200 bg-white p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="font-semibold text-slate-900">Order #{order.id}</h2>
                <p className="text-xs text-slate-500">
                  {new Date(order.createdAt).toLocaleString()}
                </p>
              </div>
              <div className="flex items-center gap-4">
                <span
                  className={`rounded-full px-2.5 py-1 text-xs font-medium ${
                    statusStyle[order.status] ?? 'bg-slate-100 text-slate-700'
                  }`}
                >
                  {order.status}
                </span>
                <span className="text-lg font-semibold text-slate-900">
                  ${Number(order.totalAmount).toFixed(2)}
                </span>
              </div>
            </div>

            <ul className="mt-4 divide-y divide-slate-100 border-t border-slate-100 pt-2 text-sm">
              {order.items?.map((item) => (
                <li key={item.id} className="flex justify-between py-2 text-slate-600">
                  <span>
                    Product #{item.productId} × {item.quantity}
                  </span>
                  <span>${Number(item.subTotal).toFixed(2)}</span>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  )
}
