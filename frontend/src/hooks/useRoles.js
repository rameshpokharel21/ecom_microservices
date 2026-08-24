import { useAuthContext } from 'react-oauth2-code-pkce'
import { KEYCLOAK_REALM } from '../config'

/*
 * Roles come from realm_access.roles - REALM roles, the same claim the gateway's
 * grantedAuthoritiesExtractor reads. Not resource_access, which holds client roles and
 * is empty here.
 *
 * Read this every time and never cache it: the claim is baked into the token at issue
 * time, so a role granted in the Keycloak console appears only after a fresh login. A
 * cached copy would just add a second way to be stale.
 *
 * NOTHING here is security. These hooks decide what a user SEES. What a user may DO is
 * decided by the gateway, which checks the signed token on every request - anyone can
 * edit tokenData in devtools and reveal every admin screen, and every call those screens
 * make will still come back 403.
 */

// Keycloak's own defaults are noise in a UI; the realm name makes default-roles-<realm>
// track the .env rather than being hardcoded to ecom-app.
const BUILTIN = ['offline_access', 'uma_authorization', `default-roles-${KEYCLOAK_REALM}`]

export function useAppRoles() {
  const { tokenData } = useAuthContext()
  return (tokenData?.realm_access?.roles ?? []).filter((r) => !BUILTIN.includes(r))
}

// No ROLE_ prefix. That is added by the gateway's converter, so Keycloak's realm role is
// named plain ADMIN - naming it ROLE_ADMIN yields the authority ROLE_ROLE_ADMIN, which
// matches no hasRole() rule and fails as a silent 403.
export function useHasRole(role) {
  return useAppRoles().includes(role)
}

export const useIsAdmin = () => useHasRole('ADMIN')
