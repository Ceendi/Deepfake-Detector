import { fireEvent, render, screen } from '@testing-library/react'
import { createGetKcContextMock } from 'keycloakify/login/KcContext'
import { describe, expect, it } from 'vitest'

import type { KcContextExtension, KcContextExtensionPerPage } from './KcContext'
import KcPage from './KcPage'

const { getKcContextMock } = createGetKcContextMock({
  kcContextExtension: { themeName: 'deepfake', properties: {} } as KcContextExtension,
  kcContextExtensionPerPage: {} as KcContextExtensionPerPage,
})

describe('Keycloak login theme', () => {
  it('renders the login form with registration, reset and remember-me options', async () => {
    const kcContext = getKcContextMock({
      pageId: 'login.ftl',
      overrides: {
        realm: {
          password: true,
          registrationAllowed: true,
          resetPasswordAllowed: true,
          rememberMe: true,
          loginWithEmailAllowed: true,
          registrationEmailAsUsername: false,
        },
        login: { username: 'alice', rememberMe: 'on' },
        message: { type: 'info', summary: 'Sesja wygasła' },
      },
    })
    const { container } = render(<KcPage kcContext={kcContext} />)

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(container.querySelector('#username')).toHaveValue('alice')
    expect(container.querySelector('input[name="rememberMe"]')).toBeChecked()
    expect(screen.getByRole('alert')).toHaveTextContent('Sesja wygasła')
    expect(container.querySelector('#kc-info a')).toHaveAttribute(
      'href',
      kcContext.url.registrationUrl,
    )
    expect(
      container.querySelector(`a[href="${kcContext.url.loginResetCredentialsUrl}"]`),
    ).not.toBeNull()

    const password = container.querySelector('#password') as HTMLInputElement
    fireEvent.click(screen.getByRole('button', { name: /show password|pokaż hasło/i }))
    expect(password.type).toBe('text')

    const form = container.querySelector('#kc-form-login') as HTMLFormElement
    form.addEventListener('submit', (event) => event.preventDefault())
    fireEvent.submit(form)
  })

  it('labels the username field by realm settings and shows field errors instead of the banner', async () => {
    const kcContext = getKcContextMock({
      pageId: 'login.ftl',
      overrides: {
        realm: { password: true, loginWithEmailAllowed: true, registrationEmailAsUsername: true },
        message: { type: 'error', summary: 'Nieprawidłowe dane' },
      },
    })
    kcContext.messagesPerField = {
      ...kcContext.messagesPerField,
      existsError: () => true,
      getFirstError: () => 'Nieprawidłowa nazwa użytkownika lub hasło.',
    }
    const { container } = render(<KcPage kcContext={kcContext} />)
    await screen.findByRole('heading', { level: 1 })
    expect(container.querySelector('#username')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Nieprawidłowa nazwa użytkownika lub hasło.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('renders the registration page with terms acceptance', async () => {
    const kcContext = getKcContextMock({
      pageId: 'register.ftl',
      overrides: { termsAcceptanceRequired: true },
    })
    const { container } = render(<KcPage kcContext={kcContext} />)
    await screen.findByRole('heading', { level: 1 })
    expect(container.querySelector('#kc-register-form')).not.toBeNull()
    const terms = container.querySelector('#termsAccepted') as HTMLInputElement | null
    if (terms) fireEvent.click(terms)
    expect(container.querySelector(`a[href="${kcContext.url.loginUrl}"]`)).not.toBeNull()
  })

  it('falls back to the default Keycloakify page for other pages', async () => {
    const kcContext = getKcContextMock({ pageId: 'info.ftl' })
    render(<KcPage kcContext={kcContext} />)
    expect(await screen.findByText('Bezpieczne logowanie przez Keycloak')).toBeInTheDocument()
  })
})
