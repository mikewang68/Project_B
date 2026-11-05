// Keys belong to a single user action. A lost response must not create another
// inventory write. Keep unresolved intentions until their original response is
// received; this is request metadata only, never a browser inventory store.
export function createRequestIntentStore(generate: () => string) {
  const pending = new Map<string, string>()
  return {
    forPayload(payload: unknown): string {
      const fingerprint = JSON.stringify(payload)
      let key = pending.get(fingerprint)
      if (!key) { key = generate(); pending.set(fingerprint, key) }
      return key
    },
    completed(payload: unknown): void { pending.delete(JSON.stringify(payload)) },
  }
}
