import { Component, type ReactNode } from 'react'

interface Props {
  children: ReactNode
  fallback?: ReactNode
}

interface State {
  hasError: boolean
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: unknown) {
    console.error('Nieobsłużony błąd UI:', error)
  }

  render() {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          <div role="alert">
            <h1>Coś poszło nie tak</h1>
            <p>Spróbuj odświeżyć stronę. Jeśli problem wróci, skontaktuj się z nami.</p>
            <button type="button" onClick={() => window.location.reload()}>
              Odśwież stronę
            </button>
          </div>
        )
      )
    }
    return this.props.children
  }
}
