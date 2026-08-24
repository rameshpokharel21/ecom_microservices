import { Link, NavLink } from 'react-router-dom'
import { useAuthContext } from 'react-oauth2-code-pkce'
import { useCart } from '../context/CartContext'
import { useIsAdmin } from '../hooks/useRoles'

const linkClass = ({ isActive }) =>
  `text-sm font-medium transition ${isActive ? 'text-indigo-600' : 'text-slate-600 hover:text-slate-900'}`

export default function NavBar() {
  const { token, tokenData, login, logOut } = useAuthContext()
  const { count } = useCart()
  // Hides links only. The gateway decides what these pages may actually do.
  const isAdmin = useIsAdmin()

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/90 backdrop-blur">
      <nav className="mx-auto flex max-w-6xl items-center gap-6 px-6 py-4">
        <Link to="/" className="text-lg font-semibold tracking-tight text-slate-900">
          ecom
        </Link>

        <div className="flex flex-1 items-center gap-5">
          <NavLink to="/" className={linkClass} end>
            Catalogue
          </NavLink>
          {token && (
            <>
              <NavLink to="/orders" className={linkClass}>
                Orders
              </NavLink>
              <NavLink to="/profile" className={linkClass}>
                Profile
              </NavLink>
            </>
          )}
          {isAdmin && (
            <>
              <span className="text-slate-300">|</span>
              <NavLink to="/admin/products" className={linkClass}>
                Manage products
              </NavLink>
              <NavLink to="/admin/users" className={linkClass}>
                Manage users
              </NavLink>
            </>
          )}
        </div>

        <div className="flex items-center gap-4">
          {token && (
            <NavLink to="/cart" className="relative text-slate-600 hover:text-slate-900">
              <span className="text-xl">🛒</span>
              {count > 0 && (
                <span className="absolute -right-2 -top-1 rounded-full bg-indigo-600 px-1.5 text-[10px] font-semibold text-white">
                  {count}
                </span>
              )}
            </NavLink>
          )}

          {token ? (
            <>
              {/* preferred_username comes from the "profile" scope in authConfig. */}
              <span className="hidden text-sm text-slate-500 sm:inline">
                {tokenData?.preferred_username ?? tokenData?.email}
              </span>
              <button
                onClick={() => logOut()}
                className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Log out
              </button>
            </>
          ) : (
            <>
              <Link
                to="/signup"
                className="text-sm font-medium text-slate-600 hover:text-slate-900"
              >
                Sign up
              </Link>
              <button
                onClick={() => login()}
                className="rounded-lg bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700"
              >
                Log in
              </button>
            </>
          )}
        </div>
      </nav>
    </header>
  )
}
