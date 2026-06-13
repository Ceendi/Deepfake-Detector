import { isRouteErrorResponse, useRouteError } from 'react-router-dom'

export function RouteError() {
  const error = useRouteError()

  if (isRouteErrorResponse(error)) {
    return (
      <div role="alert">
        <h2>{error.status === 404 ? 'Nie znaleziono' : 'Błąd'}</h2>
        <p>{error.statusText}</p>
      </div>
    )
  }

  return (
    <div role="alert">
      <h2>Coś poszło nie tak</h2>
      <p>Nie udało się wyświetlić tej strony.</p>
    </div>
  )
}
