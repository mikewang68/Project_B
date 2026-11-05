import { describe, expect, it } from 'vitest'
import { trustedManagementUrl } from './centralManagement'

describe('central personnel management navigation', () => {
  it('uses only the configured HTTPS management endpoint', () => {
    expect(trustedManagementUrl(' https://iam.example.test/management ')).toBe('https://iam.example.test/management')
  })
  it('rejects credentials and executable, relative or insecure URLs', () => {
    for (const value of [undefined, '', '/management', 'javascript:alert(1)', 'http://iam.example.test/', 'https://user:secret@iam.example.test/']) {
      expect(trustedManagementUrl(value)).toBeNull()
    }
  })
})
