import { useEffect, useRef, useState } from 'react'

import { Link, NavLink } from 'react-router-dom'

import { clsx } from 'clsx'
import { ShieldCheck, ChevronDown, Menu, X, User, Settings, LogOut } from 'lucide-react'

import { useAuth } from '@/context/auth-context'
import { accountManagement } from '@/auth/keycloak'

import styles from './Header.module.css'

// Jedno źródło prawdy dla pozycji nawigacji — używane i w desktopie, i w menu mobilnym.
const NAV_LINKS = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/upload', label: 'Upload' },
  { to: '/history', label: 'Historia' },
  { to: '/profile', label: 'Profil' },
] as const

// Inicjały do awatara: 2 pierwsze litery z imienia+nazwiska, a w razie braku — z nazwy/maila.
function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}

export function Header() {
  const { user, logout } = useAuth()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [userMenuOpen, setUserMenuOpen] = useState(false)
  const userMenuRef = useRef<HTMLDivElement>(null)

  const displayName = user?.name ?? user?.username ?? user?.email ?? 'Użytkownik'

  // Zamknij dropdown użytkownika po kliknięciu poza nim (typowy wzorzec dla menu/popoverów).
  useEffect(() => {
    if (!userMenuOpen) return
    function onPointerDown(event: MouseEvent) {
      if (userMenuRef.current && !userMenuRef.current.contains(event.target as Node)) {
        setUserMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [userMenuOpen])

  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <NavLink to="/dashboard" className={styles.brand} onClick={() => setMobileOpen(false)}>
          <span className={styles.brandMark} aria-hidden="true">
            <ShieldCheck size={20} strokeWidth={2.4} />
          </span>
          <span className={styles.brandName}>DeepfakeDetector</span>
        </NavLink>

        <nav className={styles.nav} aria-label="Główna nawigacja">
          {NAV_LINKS.map(({ to, label }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) => clsx(styles.navLink, isActive && styles.navLinkActive)}
            >
              {label}
            </NavLink>
          ))}
        </nav>

        <div className={styles.right}>
          <div className={styles.userMenu} ref={userMenuRef}>
            <button
              type="button"
              className={styles.userButton}
              onClick={() => setUserMenuOpen((open) => !open)}
              aria-haspopup="menu"
              aria-expanded={userMenuOpen}
            >
              <span className={styles.avatar} aria-hidden="true">
                {initials(displayName)}
              </span>
              <span className={styles.userName}>{displayName}</span>
              <ChevronDown size={16} strokeWidth={2.4} aria-hidden="true" />
            </button>

            {userMenuOpen && (
              <div className={styles.dropdown} role="menu">
                <Link
                  to="/profile"
                  role="menuitem"
                  className={styles.dropdownItem}
                  onClick={() => setUserMenuOpen(false)}
                >
                  <User size={16} strokeWidth={2.4} aria-hidden="true" />
                  Profil
                </Link>
                <button
                  type="button"
                  role="menuitem"
                  className={styles.dropdownItem}
                  onClick={() => {
                    setUserMenuOpen(false)
                    accountManagement()
                  }}
                >
                  <Settings size={16} strokeWidth={2.4} aria-hidden="true" />
                  Ustawienia
                </button>
                <hr className={styles.dropdownSep} />
                <button
                  type="button"
                  role="menuitem"
                  className={clsx(styles.dropdownItem, styles.dropdownItemDanger)}
                  onClick={logout}
                >
                  <LogOut size={16} strokeWidth={2.4} aria-hidden="true" />
                  Wyloguj
                </button>
              </div>
            )}
          </div>

          <button
            type="button"
            className={styles.hamburger}
            onClick={() => setMobileOpen((open) => !open)}
            aria-label={mobileOpen ? 'Zamknij menu' : 'Otwórz menu'}
            aria-expanded={mobileOpen}
          >
            {mobileOpen ? (
              <X size={22} strokeWidth={2.4} aria-hidden="true" />
            ) : (
              <Menu size={22} strokeWidth={2.4} aria-hidden="true" />
            )}
          </button>
        </div>
      </div>

      {mobileOpen && (
        <nav className={styles.mobileNav} aria-label="Główna nawigacja (mobilna)">
          {NAV_LINKS.map(({ to, label }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                clsx(styles.mobileLink, isActive && styles.navLinkActive)
              }
              onClick={() => setMobileOpen(false)}
            >
              {label}
            </NavLink>
          ))}
        </nav>
      )}
    </header>
  )
}
