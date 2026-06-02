interface SpinnerProps {
  label?: string
}

export function Spinner({ label = 'Ładowanie' }: SpinnerProps) {
  return (
    <span role="status">
      <span aria-hidden="true" /> //Animacja
      <span className="visually-hidden">{label}</span>
    </span>
  )
}
