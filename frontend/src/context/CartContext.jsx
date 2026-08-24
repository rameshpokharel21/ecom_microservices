import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { useAuthContext } from 'react-oauth2-code-pkce'
import * as cartApi from '../api/cart'
import { readMessage } from '../api/client'
import { useToast } from './ToastContext'

const CartContext = createContext(null)

export const useCart = () => {
  const ctx = useContext(CartContext)
  if (!ctx) throw new Error('useCart must be used inside <CartProvider>')
  return ctx
}

/**
 * The server is the source of truth. POST /api/carts returns ResponseEntity<Void> -
 * there is no body to merge into local state - so add/remove re-fetch rather than
 * mutating optimistically. That is a constraint of the API, not a style choice.
 *
 * This is context rather than page state because the NavBar badge and the Cart page
 * must never disagree about the count.
 */
export function CartProvider({ children }) {
  const { token } = useAuthContext()
  const { showToast } = useToast()
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(async () => {
    if (!token) {
      setItems([])
      return
    }
    setLoading(true)
    try {
      setItems(await cartApi.getCart())
    } catch (err) {
      // 401 is already handled globally by the response interceptor; anything else here
      // means the cart genuinely could not be read.
      if (err?.response?.status !== 401) showToast(readMessage(err))
    } finally {
      setLoading(false)
    }
  }, [token, showToast])

  // Refetch on login and clear on logout - both are token transitions.
  useEffect(() => {
    refresh()
  }, [refresh])

  const addItem = useCallback(
    async (productId, quantity = 1) => {
      await cartApi.addToCart(productId, quantity)
      await refresh()
    },
    [refresh],
  )

  const removeItem = useCallback(
    async (productId) => {
      await cartApi.removeFromCart(productId)
      await refresh()
    },
    [refresh],
  )

  const clear = useCallback(() => setItems([]), [])

  const count = useMemo(
    () => items.reduce((sum, item) => sum + (item.quantity ?? 0), 0),
    [items],
)

  const total = useMemo(
    () => items.reduce((sum, item) => sum + Number(item.subTotal ?? 0), 0),
    [items],
  )

  const value = useMemo(
    () => ({ items, count, total, loading, refresh, addItem, removeItem, clear }),
    [items, count, total, loading, refresh, addItem, removeItem, clear],
  )

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}
