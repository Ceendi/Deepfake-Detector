import { useState } from 'react'

import { LogIn, Eye, EyeOff } from 'lucide-react'

import type { JSX } from 'keycloakify/tools/JSX'
import { kcSanitize } from 'keycloakify/lib/kcSanitize'
import { useIsPasswordRevealed } from 'keycloakify/tools/useIsPasswordRevealed'
import type { PageProps } from 'keycloakify/login/pages/PageProps'

import { Button } from '@/components/ui/Button/Button'

import type { KcContext } from '../KcContext'
import type { I18n } from '../i18n'
import styles from '../theme.module.css'

// Custom ekran logowania (login.ftl) — formularz na tokenach/komponentach apki.
// Całe wiring kcContext (action, name, błędy, linki) zachowane; pominięto social/
// passkey (realm „deepfake" ich nie używa).
export default function Login(props: PageProps<Extract<KcContext, { pageId: 'login.ftl' }>, I18n>) {
  const { kcContext, i18n, doUseDefaultCss, Template, classes } = props

  const { realm, url, usernameHidden, login, auth, registrationDisabled, messagesPerField } =
    kcContext

  const { msg, msgStr } = i18n

  const [isSubmitting, setIsSubmitting] = useState(false)

  const hasError = messagesPerField.existsError('username', 'password')

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      displayMessage={!hasError}
      headerNode={msg('loginAccountTitle')}
      displayInfo={realm.password && realm.registrationAllowed && !registrationDisabled}
      infoNode={
        <span>
          {msg('noAccount')}{' '}
          <a href={url.registrationUrl} className={styles.link}>
            {msg('doRegister')}
          </a>
        </span>
      }
    >
      {realm.password && (
        <form
          id="kc-form-login"
          className={styles.form}
          onSubmit={() => {
            setIsSubmitting(true)
            return true
          }}
          action={url.loginAction}
          method="post"
        >
          {!usernameHidden && (
            <div className={styles.group}>
              <label htmlFor="username" className={styles.label}>
                {!realm.loginWithEmailAllowed
                  ? msg('username')
                  : !realm.registrationEmailAsUsername
                    ? msg('usernameOrEmail')
                    : msg('email')}
              </label>
              <input
                id="username"
                name="username"
                type="text"
                className={styles.input}
                defaultValue={login.username ?? ''}
                autoFocus
                autoComplete="username"
                aria-invalid={hasError}
              />
            </div>
          )}

          <div className={styles.group}>
            <label htmlFor="password" className={styles.label}>
              {msg('password')}
            </label>
            <PasswordWrapper i18n={i18n} passwordInputId="password">
              <input
                id="password"
                name="password"
                type="password"
                className={styles.input}
                autoComplete="current-password"
                aria-invalid={hasError}
              />
            </PasswordWrapper>
          </div>

          {hasError && (
            <span
              className={styles.inputError}
              aria-live="polite"
              dangerouslySetInnerHTML={{
                __html: kcSanitize(messagesPerField.getFirstError('username', 'password')),
              }}
            />
          )}

          <div className={styles.options}>
            {realm.rememberMe && !usernameHidden && (
              <label className={styles.checkbox}>
                <input
                  type="checkbox"
                  name="rememberMe"
                  className={styles.checkboxInput}
                  defaultChecked={!!login.rememberMe}
                />{' '}
                {msg('rememberMe')}
              </label>
            )}
            {realm.resetPasswordAllowed && (
              <a href={url.loginResetCredentialsUrl} className={styles.link}>
                {msg('doForgotPassword')}
              </a>
            )}
          </div>

          <input
            type="hidden"
            id="id-hidden-input"
            name="credentialId"
            value={auth.selectedCredential}
          />

          <Button
            type="submit"
            variant="primary"
            size="md"
            leftIcon={LogIn}
            isLoading={isSubmitting}
            className={styles.actionBtn}
          >
            {msgStr('doLogIn')}
          </Button>
        </form>
      )}
    </Template>
  )
}

// Input hasła + przycisk podglądu. Input jest „children" (własność rodzica), żeby
// re-render przy toggle nie nadpisywał atrybutu type ustawianego przez hook.
function PasswordWrapper(props: { i18n: I18n; passwordInputId: string; children: JSX.Element }) {
  const { i18n, passwordInputId, children } = props
  const { msgStr } = i18n
  const { isPasswordRevealed, toggleIsPasswordRevealed } = useIsPasswordRevealed({
    passwordInputId,
  })

  return (
    <div className={styles.inputGroup}>
      {children}
      <button
        type="button"
        className={styles.passwordToggle}
        aria-label={msgStr(isPasswordRevealed ? 'hidePassword' : 'showPassword')}
        aria-controls={passwordInputId}
        onClick={toggleIsPasswordRevealed}
      >
        {isPasswordRevealed ? (
          <EyeOff size={18} strokeWidth={2} aria-hidden />
        ) : (
          <Eye size={18} strokeWidth={2} aria-hidden />
        )}
      </button>
    </div>
  )
}
