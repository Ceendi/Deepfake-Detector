import { clsx } from 'clsx'
import { Mail, ShieldCheck, User, ExternalLink, Info, Moon, Sun, Globe, LogOut } from 'lucide-react'

import { useAuth } from '@/context/auth-context'
import { useTheme } from '@/context/theme-context'
import { accountManagement } from '@/auth/keycloak'
import { Button } from '@/components/ui/Button/Button'

import styles from './Profile.module.css'

// 2 pierwsze litery z imienia+nazwiska, a w razie braku — z nazwy/maila (jak w nagłówku).
function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}

export default function Profile() {
  const { user, logout } = useAuth()
  const { theme, setTheme } = useTheme()

  const displayName = user?.name ?? user?.username ?? 'Użytkownik'
  const email = user?.email

  return (
    <div className={styles.page}>
      <header className={styles.head}>
        <h1>Profil</h1>
        <p className={styles.subtitle}>Twoje dane konta i ustawienia aplikacji.</p>
      </header>

      {/* --- Tożsamość -------------------------------------------------------- */}
      <section className={styles.card}>
        <div className={styles.identity}>
          <span className={styles.avatar} aria-hidden="true">
            {initials(displayName)}
          </span>
          <div className={styles.identityMain}>
            <h2 className={styles.name}>{displayName}</h2>
            {email && (
              <p className={styles.email}>
                <Mail size={16} strokeWidth={2} aria-hidden="true" />
                <span>{email}</span>
              </p>
            )}
            <span className={styles.managedBadge}>
              <ShieldCheck size={14} strokeWidth={2.4} aria-hidden="true" />
              Konto zarządzane przez Keycloak
            </span>
          </div>
        </div>
      </section>

      {/* --- Ustawienia konta ------------------------------------------------- */}
      <section className={styles.card}>
        <h3 className={styles.sectionLabel}>Ustawienia konta</h3>
        <div className={styles.settingRow}>
          <span className={styles.settingIcon} aria-hidden="true">
            <User size={18} strokeWidth={2} />
          </span>
          <div className={styles.settingText}>
            <div className={styles.settingTitle}>Profil i hasło</div>
            <p className={styles.settingDesc}>
              Edycja danych, zmiana hasła i bezpieczeństwo konta.
            </p>
          </div>
          <div className={styles.settingAction}>
            <Button
              variant="primary"
              size="md"
              rightIcon={ExternalLink}
              onClick={accountManagement}
            >
              Edytuj profil
            </Button>
          </div>
        </div>
        <p className={styles.note}>
          <Info size={16} strokeWidth={2} aria-hidden="true" />
          <span>
            Zmiany danych i hasła odbywają się w bezpiecznej konsoli konta (Keycloak), w nowej
            karcie.
          </span>
        </p>
      </section>

      {/* --- Preferencje aplikacji (placeholdery V2) -------------------------- */}
      <section className={styles.card}>
        <h3 className={styles.sectionLabel}>Preferencje aplikacji</h3>

        <div className={styles.settingRow}>
          <span className={styles.settingIcon} aria-hidden="true">
            <Moon size={18} strokeWidth={2} />
          </span>
          <div className={styles.settingText}>
            <div className={styles.settingTitle}>Motyw</div>
            <p className={styles.settingDesc}>
              Przełącz między jasnym a ciemnym wyglądem aplikacji.
            </p>
          </div>
          <div className={styles.settingAction}>
            <div className={styles.segment} role="group" aria-label="Motyw">
              <button
                type="button"
                className={clsx(styles.segmentBtn, theme === 'light' && styles.segmentBtnActive)}
                aria-pressed={theme === 'light'}
                onClick={() => setTheme('light')}
              >
                <Sun size={14} strokeWidth={2.4} aria-hidden="true" /> Jasny
              </button>
              <button
                type="button"
                className={clsx(styles.segmentBtn, theme === 'dark' && styles.segmentBtnActive)}
                aria-pressed={theme === 'dark'}
                onClick={() => setTheme('dark')}
              >
                <Moon size={14} strokeWidth={2.4} aria-hidden="true" /> Ciemny
              </button>
            </div>
          </div>
        </div>

        <hr className={styles.divider} />

        <div className={styles.settingRow}>
          <span className={styles.settingIcon} aria-hidden="true">
            <Globe size={18} strokeWidth={2} />
          </span>
          <div className={styles.settingText}>
            <div className={styles.settingTitle}>
              Język <span className={styles.soon}>Wkrótce · V2</span>
            </div>
            <p className={styles.settingDesc}>Język interfejsu aplikacji.</p>
          </div>
          <div className={styles.settingAction}>
            <div className={styles.segment} role="group" aria-label="Język (wkrótce)">
              <button type="button" className={styles.segmentBtn} disabled>
                PL
              </button>
              <button type="button" className={styles.segmentBtn} disabled>
                EN
              </button>
            </div>
          </div>
        </div>
      </section>

      {/* --- Wyloguj ---------------------------------------------------------- */}
      <section className={clsx(styles.card, styles.logoutCard)}>
        <div className={styles.settingText}>
          <div className={styles.settingTitle}>Wyloguj się</div>
          <p className={styles.settingDesc}>Zakończ sesję na tym urządzeniu.</p>
        </div>
        <button type="button" className={styles.logoutButton} onClick={logout}>
          <LogOut size={16} strokeWidth={2.4} aria-hidden="true" />
          Wyloguj się
        </button>
      </section>
    </div>
  )
}
