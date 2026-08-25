import { api } from './client'

// The one route the gateway leaves open (POST /api/users, permitAll). It creates the
// Keycloak account AND the Mongo profile, but returns no token - see Signup.jsx.
export const signup = (payload) => api.post('/api/users', payload).then((r) => r.data)

// /me, not /{sub}. The id-based route is ADMIN-only now, and this also stops the client
// depending on the _id == sub decision - if that ever changes, only user-service moves.
export const getProfile = () => api.get('/api/users/me').then((r) => r.data)

// A full REPLACE, not a patch - UserService.updateUser assigns every field from the
// request, so anything the payload omits is written back as null.
export const updateProfile = (payload) => api.put('/api/users/me', payload).then((r) => r.data)

// --- ADMIN only. The gateway answers 403 to a customer token; these are here so the
// admin screens have somewhere to call, not because the client decides who may call them.
export const listUsers = () => api.get('/api/users').then((r) => r.data)

// Deletes the Mongo profile AND the Keycloak account - see UserService.removeUser.
export const deleteUser = (id) => api.delete(`/api/users/${id}`)
