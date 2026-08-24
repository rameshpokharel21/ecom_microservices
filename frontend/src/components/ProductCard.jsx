import { Link } from 'react-router-dom'

export default function ProductCard({ product, onAdd, adding }) {
  const outOfStock = (product.stockQuantity ?? 0) <= 0

  return (
    <div className="flex flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm transition hover:shadow-md">
      <Link to={`/products/${product.id}`} className="block">
        <div className="flex h-40 items-center justify-center bg-slate-100">
          {product.imageUrl ? (
            <img src={product.imageUrl} alt={product.name} className="h-full w-full object-cover" />
          ) : (
            <span className="text-3xl text-slate-300">📦</span>
          )}
        </div>
      </Link>

      <div className="flex flex-1 flex-col p-4">
        <Link to={`/products/${product.id}`}>
          <h3 className="font-medium text-slate-900 hover:text-indigo-600">{product.name}</h3>
        </Link>
        <p className="mt-1 line-clamp-2 text-sm text-slate-500">{product.description}</p>

        <div className="mt-3 flex items-baseline justify-between">
          <span className="text-lg font-semibold text-slate-900">
            ${Number(product.price).toFixed(2)}
          </span>
          <span className={`text-xs ${outOfStock ? 'text-red-600' : 'text-slate-500'}`}>
            {outOfStock ? 'Out of stock' : `${product.stockQuantity} in stock`}
          </span>
        </div>

        <button
          onClick={() => onAdd(product)}
          disabled={outOfStock || adding}
          className="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          {adding ? 'Adding…' : 'Add to cart'}
        </button>
      </div>
    </div>
  )
}
