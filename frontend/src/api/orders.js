import { api } from './client'

// No body: order-service reads the cart server-side from the X-User-ID the gateway
// injected, so there is nothing for the client to send or to get wrong.
export const placeOrder = () => api.post('/api/orders').then((r) => r.data)

export const listOrders = () => api.get('/api/orders').then((r) => r.data)
