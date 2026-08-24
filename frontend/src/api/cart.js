import { api } from './client'

export const getCart = () => api.get('/api/carts').then((r) => r.data)

// No String() any more: productId is a Long end to end, so it stays a JSON number and
// product.id === cartItem.productId now actually holds in JS.
export const addToCart = (productId, quantity) =>
  api.post('/api/carts', { productId, quantity })

export const removeFromCart = (productId) =>
  api.delete(`/api/carts/items/${productId}`)
