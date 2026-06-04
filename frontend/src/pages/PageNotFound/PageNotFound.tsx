import { Link } from 'react-router-dom'

export default function PageNotFound() {
  return (
    <main>
      <h1>404 — Nie znaleziono strony</h1>
      <p>Strona, której szukasz, nie istnieje lub została przeniesiona.</p>
      <Link to="/">Wróć na stronę główną</Link>
    </main>
  )
}
