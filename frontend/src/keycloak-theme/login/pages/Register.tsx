import { useState, useLayoutEffect } from 'react'

import { UserPlus } from 'lucide-react'

import type { JSX } from 'keycloakify/tools/JSX'
import type { LazyOrNot } from 'keycloakify/tools/LazyOrNot'
import { kcSanitize } from 'keycloakify/lib/kcSanitize'
import { getKcClsx } from 'keycloakify/login/lib/kcClsx'
import type { UserProfileFormFieldsProps } from 'keycloakify/login/UserProfileFormFieldsProps'
import type { PageProps } from 'keycloakify/login/pages/PageProps'

import { Button } from '@/components/ui/Button/Button'

import type { KcContext } from '../KcContext'
import type { I18n } from '../i18n'
import styles from '../theme.module.css'

type RegisterProps = PageProps<Extract<KcContext, { pageId: 'register.ftl' }>, I18n> & {
  UserProfileFormFields: LazyOrNot<(props: UserProfileFormFieldsProps) => JSX.Element>
  doMakeUserConfirmPassword: boolean
}

// Custom ekran rejestracji (register.ftl). Pola są dynamiczne (UserProfileFormFields
// zależne od konfiguracji profilu w Keycloaku) — stylizujemy je przez classes-map
// (patrz KcPage). Tu kontrolujemy układ, zgody, recaptcha i przycisk.
export default function Register(props: RegisterProps) {
  const {
    kcContext,
    i18n,
    doUseDefaultCss,
    Template,
    classes,
    UserProfileFormFields,
    doMakeUserConfirmPassword,
  } = props

  const { kcClsx } = getKcClsx({ doUseDefaultCss, classes })

  const {
    messageHeader,
    url,
    messagesPerField,
    recaptchaRequired,
    recaptchaVisible,
    recaptchaSiteKey,
    recaptchaAction,
    termsAcceptanceRequired,
  } = kcContext

  const { msg, msgStr, advancedMsg } = i18n

  const [isFormSubmittable, setIsFormSubmittable] = useState(false)
  const [areTermsAccepted, setAreTermsAccepted] = useState(false)

  useLayoutEffect(() => {
    ;(window as unknown as { onSubmitRecaptcha?: () => void }).onSubmitRecaptcha = () => {
      ;(document.getElementById('kc-register-form') as HTMLFormElement | null)?.requestSubmit()
    }
    return () => {
      delete (window as unknown as { onSubmitRecaptcha?: () => void }).onSubmitRecaptcha
    }
  }, [])

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      headerNode={messageHeader !== undefined ? advancedMsg(messageHeader) : msg('registerTitle')}
      displayMessage={messagesPerField.exists('global')}
      displayRequiredFields
    >
      <form
        id="kc-register-form"
        className={styles.form}
        action={url.registrationAction}
        method="post"
      >
        <UserProfileFormFields
          kcContext={kcContext}
          i18n={i18n}
          kcClsx={kcClsx}
          onIsFormSubmittableValueChange={setIsFormSubmittable}
          doMakeUserConfirmPassword={doMakeUserConfirmPassword}
        />

        {termsAcceptanceRequired && (
          <TermsAcceptance
            i18n={i18n}
            messagesPerField={messagesPerField}
            areTermsAccepted={areTermsAccepted}
            onAreTermsAcceptedValueChange={setAreTermsAccepted}
          />
        )}

        {recaptchaRequired && (recaptchaVisible || recaptchaAction === undefined) && (
          <div
            className="g-recaptcha"
            data-size="compact"
            data-sitekey={recaptchaSiteKey}
            data-action={recaptchaAction}
          />
        )}

        {recaptchaRequired && !recaptchaVisible && recaptchaAction !== undefined ? (
          <button
            className={`${styles.kcButton} ${styles.kcButtonPrimary} ${styles.kcButtonBlock} g-recaptcha`}
            data-sitekey={recaptchaSiteKey}
            data-callback="onSubmitRecaptcha"
            data-action={recaptchaAction}
            type="submit"
          >
            {msg('doRegister')}
          </button>
        ) : (
          <Button
            type="submit"
            variant="primary"
            size="md"
            leftIcon={UserPlus}
            disabled={!isFormSubmittable || (termsAcceptanceRequired && !areTermsAccepted)}
            className={styles.actionBtn}
          >
            {msgStr('doRegister')}
          </Button>
        )}

        <div className={styles.info}>
          <a href={url.loginUrl} className={styles.link}>
            {msg('backToLogin')}
          </a>
        </div>
      </form>
    </Template>
  )
}

function TermsAcceptance(props: {
  i18n: I18n
  messagesPerField: Pick<KcContext['messagesPerField'], 'existsError' | 'get'>
  areTermsAccepted: boolean
  onAreTermsAcceptedValueChange: (areTermsAccepted: boolean) => void
}) {
  const { i18n, messagesPerField, areTermsAccepted, onAreTermsAcceptedValueChange } = props
  const { msg } = i18n

  return (
    <div className={styles.group}>
      <div>
        {msg('termsTitle')}
        <div id="kc-registration-terms-text">{msg('termsText')}</div>
      </div>
      <label className={styles.checkbox}>
        <input
          type="checkbox"
          id="termsAccepted"
          name="termsAccepted"
          className={styles.checkboxInput}
          checked={areTermsAccepted}
          onChange={(e) => onAreTermsAcceptedValueChange(e.target.checked)}
          aria-invalid={messagesPerField.existsError('termsAccepted')}
        />{' '}
        {msg('acceptTerms')}
      </label>
      {messagesPerField.existsError('termsAccepted') && (
        <span
          id="input-error-terms-accepted"
          className={styles.inputError}
          aria-live="polite"
          dangerouslySetInnerHTML={{ __html: kcSanitize(messagesPerField.get('termsAccepted')) }}
        />
      )}
    </div>
  )
}
