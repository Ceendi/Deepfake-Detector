import { Outlet } from 'react-router-dom'
import { Suspense } from 'react'

import { Spinner } from '@/components/ui/Spinner/Spinner'

export function Layout() {
  return (
    <div>
      <header>Header</header>
      <main>
        <Suspense fallback={<Spinner />}>
          <Outlet />
        </Suspense>
      </main>
    </div>
  )
}
