import request from '@/utils/request'

export function exchangeSsoTicket(ticket) {
  return request({
    url: '/api/auth/sso/exchange', method: 'post', data: { ticket },
    // Do not attach a previous identity or put this single-use credential in the deduplication cache.
    headers: { isToken: false, repeatSubmit: false }
  })
}
