import { Upload, UploadCloud } from 'lucide-react'

import { LinkButton } from '@/components/ui/LinkButton/LinkButton'

import styles from '../Dashboard.module.css'

// Stan pusty: użytkownik nie ma jeszcze żadnych analiz — onboarding zamiast dashboardu.
export function DashboardEmpty({ firstName }: { firstName: string }) {
  return (
    <div className={styles.page}>
      <div className={styles.greeting}>
        <h1>Cześć, {firstName}.</h1>
        <p className={styles.subtitle}>Nie masz jeszcze żadnych analiz.</p>
      </div>

      <div className={styles.empty}>
        <span className={styles.emptyIcon} aria-hidden="true">
          <Upload size={36} strokeWidth={2} />
        </span>
        <h2>Zacznij od pierwszej analizy</h2>
        <p className={styles.emptyText}>
          Wgraj plik wideo lub audio — sprawdzimy autentyczność ścieżki obrazu i dźwięku, podamy
          werdykt z poziomem pewności oraz pokażemy heatmapę Grad-CAM.
        </p>
        <LinkButton to="/upload" variant="primary" size="md" leftIcon={UploadCloud}>
          Wgraj pierwszy plik
        </LinkButton>
        <p className={styles.emptyHint}>Obsługiwane: MP4 · MOV · WAV · MP3 — do 500 MB</p>

        <ol className={styles.steps}>
          <li className={styles.step}>
            <span className={styles.stepNum}>1</span> Wgraj plik
          </li>
          <span className={styles.stepSep} aria-hidden="true">
            ›
          </span>
          <li className={styles.step}>
            <span className={styles.stepNum}>2</span> Analiza AI
          </li>
          <span className={styles.stepSep} aria-hidden="true">
            ›
          </span>
          <li className={styles.step}>
            <span className={styles.stepNum}>3</span> Werdykt + dowody
          </li>
        </ol>
      </div>
    </div>
  )
}
