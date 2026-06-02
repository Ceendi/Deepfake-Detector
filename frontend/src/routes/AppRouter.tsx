import { lazy, Suspense } from 'react'

import { createBrowserRouter, RouterProvider } from 'react-router-dom'

import { Layout } from '@/components/layout/Layout'
import { ProtectedRoute } from '@/routes/ProtectedRoute'
import { Spinner } from '@/components/ui/Spinner/Spinner'

const LazyDashboard = lazy(() => import('@/pages/Dashboard/Dashboard'))
const LazyLogin = lazy(() => import('@/pages/Login/Login'))
const LazyUpload = lazy(() => import('@/pages/Upload/Upload'))
const LazyAnalysisResult = lazy(() => import('@/pages/AnalysisResult/AnalysisResult'))
const LazyHistory = lazy(() => import('@/pages/History/History'))
const LazyProfile = lazy(() => import('@/pages/Profile/Profile'))

const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      {
        element: <ProtectedRoute />,
        children: [
          {
            index: true,
            element: <LazyDashboard />,
          },
          {
            path: '/dashboard',
            element: <LazyDashboard />,
          },

          {
            path: '/upload',
            element: <LazyUpload />,
          },
          {
            path: '/analysis-result',
            element: <LazyAnalysisResult />,
          },
          {
            path: '/history',
            element: <LazyHistory />,
          },
          {
            path: '/profile',
            element: <LazyProfile />,
          },
        ],
      },
    ],
  },
  {
    path: '/login',
    element: (
      <Suspense fallback={<Spinner />}>
        <LazyLogin />
      </Suspense>
    ),
  },
  {
    path: '*',
    element: <div>Bład</div>,
  },
])

export function AppRouter() {
  return <RouterProvider router={router} />
}
