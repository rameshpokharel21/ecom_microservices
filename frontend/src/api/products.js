import { api } from './client'

export const listProducts = () => api.get('/api/products').then((r) => r.data)

export const getProduct = (id) => api.get(`/api/products/${id}`).then((r) => r.data)

// ProductController exposes /search with a "keyword" query parameter.
export const searchProducts = (keyword) =>
  api.get('/api/products/search', { params: { keyword } }).then((r) => r.data)

// --- ADMIN only at the gateway (POST/PUT/PATCH/DELETE on /api/products/**). Reads above
// stay open to any logged-in user, which is why only the writes live down here.
export const createProduct = (payload) => api.post('/api/products', payload).then((r) => r.data)

export const updateProduct = (id, payload) =>
  api.put(`/api/products/${id}`, payload).then((r) => r.data)

export const deleteProduct = (id) => api.delete(`/api/products/${id}`)
