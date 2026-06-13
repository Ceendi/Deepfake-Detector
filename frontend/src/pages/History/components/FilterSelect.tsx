import { useEffect, useRef, useState } from 'react'

import { ChevronDown, Check } from 'lucide-react'
import { clsx } from 'clsx'

import type { FilterOption } from '../filters'
import styles from '../History.module.css'

interface FilterSelectProps {
  label: string // prefiks na przycisku, np. „Status"
  value: string
  options: FilterOption[]
  onChange: (value: string) => void
}

// Dropdown filtra: przycisk „label: wybrane ▾" + popover z opcjami. Zamyka się kliknięciem poza
// obszar i Escape. Aktywny (≠ wartość domyślna) podświetlamy akcentem.
export function FilterSelect({ label, value, options, onChange }: FilterSelectProps) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)

  const selected = options.find((option) => option.value === value) ?? options[0]
  const isActive = value !== options[0].value

  useEffect(() => {
    if (!open) return

    function handlePointer(event: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) setOpen(false)
    }
    function handleKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }

    document.addEventListener('mousedown', handlePointer)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handlePointer)
      document.removeEventListener('keydown', handleKey)
    }
  }, [open])

  return (
    <div className={styles.filterWrap} ref={wrapRef}>
      <button
        type="button"
        className={clsx(styles.filter, isActive && styles.filterActive)}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        {label}: {selected.label}
        <ChevronDown
          size={16}
          strokeWidth={2.4}
          className={styles.filterChevron}
          aria-hidden="true"
        />
      </button>

      {open && (
        <ul className={styles.menu} role="listbox" aria-label={label}>
          {options.map((option) => (
            <li key={option.value}>
              <button
                type="button"
                role="option"
                aria-selected={option.value === value}
                className={clsx(styles.menuItem, option.value === value && styles.menuItemActive)}
                onClick={() => {
                  onChange(option.value)
                  setOpen(false)
                }}
              >
                {option.label}
                {option.value === value && <Check size={16} strokeWidth={2.4} aria-hidden="true" />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
