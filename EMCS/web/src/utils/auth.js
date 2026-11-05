import Cookies from 'js-cookie'

const TokenKey = 'EMCS-Token'
let nativeSession = false
export function hasNativeSession() { return nativeSession }
export function setNativeSession(value) { nativeSession = Boolean(value) }

export function getToken() {
  return Cookies.get(TokenKey)
}

export function setToken(token) {
  return Cookies.set(TokenKey, token)
}

export function removeToken() {
  nativeSession = false
  return Cookies.remove(TokenKey)
}
