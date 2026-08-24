import { Route, Routes } from 'react-router-dom'
import NavBar from './components/NavBar'
import ProtectedRoute from './components/ProtectedRoute'
import AdminRoute from './components/AdminRoute'
import AdminUsers from './pages/AdminUsers'
import AdminProducts from './pages/AdminProducts'
import Catalogue from './pages/Catalogue'
import ProductDetail from './pages/ProductDetail'
import Cart from './pages/Cart'
import Orders from './pages/Orders'
import Profile from './pages/Profile'
import Signup from './pages/Signup'

const protect = (element) => <ProtectedRoute>{element}</ProtectedRoute>
const adminOnly = (element) => <AdminRoute>{element}</AdminRoute>

export default function App() {
  return (
    <div className="min-h-screen">
      <NavBar />
      <main className="mx-auto max-w-6xl px-6">
        <Routes>
          {/* "/" is also the OAuth redirectUri: the library consumes the ?code= here
              before this renders, which is why Catalogue must tolerate having no token. */}
          <Route path="/" element={<Catalogue />} />
          <Route path="/signup" element={<Signup />} />
          <Route path="/products/:id" element={protect(<ProductDetail />)} />
          <Route path="/cart" element={protect(<Cart />)} />
          <Route path="/orders" element={protect(<Orders />)} />
          <Route path="/profile" element={protect(<Profile />)} />
          {/* Guarded twice over: AdminRoute hides them, and the gateway's hasRole("ADMIN")
              rules 403 the calls regardless of what the browser thinks. */}
          <Route path="/admin/users" element={adminOnly(<AdminUsers />)} />
          <Route path="/admin/products" element={adminOnly(<AdminProducts />)} />
          <Route
            path="*"
            element={<p className="py-20 text-center text-slate-500">Page not found.</p>}
          />
        </Routes>
      </main>
    </div>
  )
}
