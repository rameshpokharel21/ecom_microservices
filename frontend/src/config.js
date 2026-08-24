/*
 * Every address the app talks to, in one place.
 *
 * These are Vite build-time substitutions, not runtime lookups - `import.meta.env.X` is
 * replaced with a literal when the bundle is built, so changing .env needs a dev-server
 * restart, not just a reload.
 *
 * Nothing secret belongs here. Only VITE_-prefixed vars are exposed to the client at all,
 * which is Vite refusing to let a stray API key reach the browser by accident. Every value
 * below is public by nature - the client id and both URLs are visible in any network tab.
 *
 * The fallbacks keep a fresh clone working with no .env at all; .env only exists to make
 * the values explicit and overridable.
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8443'
export const KEYCLOAK_REALM = import.meta.env.VITE_KEYCLOAK_REALM ?? 'ecom-app'
export const KEYCLOAK_CLIENT_ID = import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'oauth2-pkce'

//window.location.origin rather than a configured value: this MUST equal the origin the
//browser is actually on, or Keycloak rejects the redirect. Reading it from the browser
//makes it impossible for the two to disagree - and it is also the string that has to be
//in Keycloak's Valid redirect URIs and Web origins, and in the gateway's CORS bean.
export const APP_ORIGIN = window.location.origin
