import { APP_ORIGIN, KEYCLOAK_CLIENT_ID, KEYCLOAK_REALM, KEYCLOAK_URL } from './config'

// Addresses come from config.js/.env now. The issuer host must still match
// cloud-gateway.yml's issuer-uri CHARACTER FOR CHARACTER - the gateway compares "iss"
// as a string, which is why KC_HOSTNAME pins it to http://localhost:8443.
const KEYCLOAK = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect`

export const authConfig = {
  clientId: KEYCLOAK_CLIENT_ID,
  authorizationEndpoint: `${KEYCLOAK}/auth`,
  tokenEndpoint: `${KEYCLOAK}/token`,
  logoutEndpoint: `${KEYCLOAK}/logout`,
  redirectUri: APP_ORIGIN,
  scope: 'openid profile email offline_access',
  // Without this an expired refresh token leaves the app in a logged-in-looking state
  // whose every call 401s. Sending the user back through the flow is the only recovery.
  onRefreshTokenExpire: (event) => event.login(),
  autoLogin: false,
}
