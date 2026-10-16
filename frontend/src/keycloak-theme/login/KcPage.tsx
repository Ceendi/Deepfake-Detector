import { Suspense, lazy } from 'react'

import type { ClassKey } from 'keycloakify/login'
import DefaultPage from 'keycloakify/login/DefaultPage'

import type { KcContext } from './KcContext'
import { useI18n } from './i18n'
import Template from './Template'
import Login from './pages/Login'
import Register from './pages/Register'

import '@/styles/reset.css'
import '@/styles/tokens.css'
import '@/styles/global.css'
import styles from './theme.module.css'

const UserProfileFormFields = lazy(() => import('keycloakify/login/UserProfileFormFields'))

const doMakeUserConfirmPassword = true

export default function KcPage(props: { kcContext: KcContext }) {
  const { kcContext } = props

  const { i18n } = useI18n({ kcContext })

  return (
    <Suspense>
      {(() => {
        switch (kcContext.pageId) {
          case 'login.ftl':
            return (
              <Login
                kcContext={kcContext}
                i18n={i18n}
                classes={classes}
                Template={Template}
                doUseDefaultCss={false}
              />
            )
          case 'register.ftl':
            return (
              <Register
                kcContext={kcContext}
                i18n={i18n}
                classes={classes}
                Template={Template}
                doUseDefaultCss={false}
                UserProfileFormFields={UserProfileFormFields}
                doMakeUserConfirmPassword={doMakeUserConfirmPassword}
              />
            )
          default:
            return (
              <DefaultPage
                kcContext={kcContext}
                i18n={i18n}
                classes={classes}
                Template={Template}
                doUseDefaultCss={false}
                UserProfileFormFields={UserProfileFormFields}
                doMakeUserConfirmPassword={doMakeUserConfirmPassword}
              />
            )
        }
      })()}
    </Suspense>
  )
}

const classes = {
  kcFormGroupClass: styles.group,
  kcLabelClass: styles.label,
  kcInputClass: styles.input,
  kcInputErrorMessageClass: styles.inputError,
  kcInputGroup: styles.inputGroup,
  kcFormPasswordVisibilityButtonClass: styles.passwordToggle,
  kcCheckboxInputClass: styles.checkboxInput,
  kcButtonClass: styles.kcButton,
  kcButtonPrimaryClass: styles.kcButtonPrimary,
  kcButtonBlockClass: styles.kcButtonBlock,
  kcFormOptionsClass: styles.options,
} satisfies { [key in ClassKey]?: string }
