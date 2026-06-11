import { useEffect } from 'react'

import { clsx } from 'clsx'
import { ShieldCheck, CircleAlert, TriangleAlert, CircleCheck, Info } from 'lucide-react'

import { kcSanitize } from 'keycloakify/lib/kcSanitize'
import type { TemplateProps } from 'keycloakify/login/TemplateProps'
import { useInitialize } from 'keycloakify/login/Template.useInitialize'

import type { I18n } from './i18n'
import type { KcContext } from './KcContext'
import styles from './theme.module.css'

const ALERT = {
  error: { Icon: CircleAlert, cls: styles.alertError },
  warning: { Icon: TriangleAlert, cls: styles.alertWarning },
  success: { Icon: CircleCheck, cls: styles.alertSuccess },
  info: { Icon: Info, cls: styles.alertInfo },
} as const

// Wspólny shell wszystkich ekranów auth (Template Keycloakify). Brand jak w apce:
// pełnoekranowe tło z poświatą, wyśrodkowana karta, logo ShieldCheck (jak Header),
// stopka. Zachowana logika KC: useInitialize (ładuje wymagane skrypty/bundle i18n),
// document.title, alerty `message`, strefa info (np. link rejestracji), tryAnotherWay,
// selektor języka (gdy >1 język).
export default function Template(props: TemplateProps<KcContext, I18n>) {
  const {
    displayInfo = false,
    displayMessage = true,
    displayRequiredFields = false,
    headerNode,
    socialProvidersNode = null,
    infoNode = null,
    documentTitle,
    kcContext,
    i18n,
    doUseDefaultCss,
    children,
  } = props

  const { msg, msgStr, currentLanguage, enabledLanguages } = i18n
  const { realm, auth, url, message, isAppInitiatedAction } = kcContext

  useEffect(() => {
    document.title = documentTitle ?? msgStr('loginTitle', realm.displayName || realm.name)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- tytuł ustawiamy raz, na mount
  }, [])

  const { isReadyToRender } = useInitialize({ kcContext, doUseDefaultCss })

  if (!isReadyToRender) {
    return null
  }

  return (
    <div className={styles.screen}>
      <section className={styles.card}>
        {enabledLanguages.length > 1 && (
          <div className={styles.locale} id="kc-locale">
            {enabledLanguages.map(({ languageTag, label, href }) => (
              <a
                key={languageTag}
                href={href}
                className={clsx(
                  styles.link,
                  languageTag === currentLanguage.languageTag && styles.localeActive,
                )}
              >
                {label}
              </a>
            ))}
          </div>
        )}

        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            <ShieldCheck size={28} strokeWidth={2.4} />
          </span>
          <span className={styles.eyebrow}>DeepfakeDetector</span>
          <h1 id="kc-page-title" className={styles.title}>
            {headerNode}
          </h1>
          {displayRequiredFields && (
            <p className={styles.requiredHint}>
              <span className={styles.requiredStar}>*</span> {msg('requiredFields')}
            </p>
          )}
        </div>

        {displayMessage &&
          message !== undefined &&
          (message.type !== 'warning' || !isAppInitiatedAction) &&
          (() => {
            const { Icon, cls } = ALERT[message.type]
            return (
              <div className={clsx(styles.alert, cls)} role="alert">
                <Icon size={18} strokeWidth={2.2} aria-hidden="true" />
                <span dangerouslySetInnerHTML={{ __html: kcSanitize(message.summary) }} />
              </div>
            )
          })()}

        {children}

        {auth !== undefined && auth.showTryAnotherWayLink && (
          <form id="kc-select-try-another-way-form" action={url.loginAction} method="post">
            <input type="hidden" name="tryAnotherWay" value="on" />
            <a
              href="#"
              className={styles.link}
              onClick={(event) => {
                document.forms['kc-select-try-another-way-form' as never].requestSubmit()
                event.preventDefault()
              }}
            >
              {msg('doTryAnotherWay')}
            </a>
          </form>
        )}

        {socialProvidersNode}

        {displayInfo && (
          <div id="kc-info" className={styles.info}>
            {infoNode}
          </div>
        )}

        <div className={styles.footer}>
          <p className={styles.note}>
            <ShieldCheck size={14} strokeWidth={2.4} aria-hidden="true" />
            <span>Bezpieczne logowanie przez Keycloak</span>
          </p>
        </div>
      </section>
    </div>
  )
}
