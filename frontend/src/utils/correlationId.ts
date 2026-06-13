// X-Correlation-Id per request — pozwala skorelować log frontu z logami backendu (D2).
// crypto.randomUUID() działa w przeglądarce (kontekst bezpieczny, w tym localhost) oraz w jsdom.
export function newCorrelationId(): string {
  return crypto.randomUUID()
}
