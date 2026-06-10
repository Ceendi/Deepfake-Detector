import { Outlet } from 'react-router-dom'
import { Suspense } from 'react'

import { Header } from '@/components/layout/Header'
import { Spinner } from '@/components/ui/Spinner/Spinner'

import styles from './Layout.module.css'

export function Layout() {
  return (
    <div className={styles.layout}>
      <Header />
      <main className={styles.main}>
        <Suspense
          fallback={
            <div className={styles.loading}>
              <Spinner size="lg" />
            </div>
          }
        >
          <Outlet />
        </Suspense>
      </main>
    </div>
  )
}
