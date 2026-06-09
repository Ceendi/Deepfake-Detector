import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Button } from '@/components/ui/Button'
import { LinkButton } from '@/components/ui/LinkButton'
import { Alert } from '@/components/ui/Alert'
import { Input } from '@/components/ui/Input'
import { FormField } from '@/components/ui/FormField'
import { ProgressBar } from '@/components/ui/ProgressBar'
import { Card, CardMedia, CardBody, CardFooter, FrameHeatmap } from '@/components/ui/Card'

import { Check, Upload, ArrowRight } from 'lucide-react'

import styles from './Playground.module.css'

type Theme = 'light' | 'system' | 'dark'

// Atrapy obrazów do podglądu Card bez backendu (klatka = szary kadr, heatmap = plamy ciepła).
const svgUri = (markup: string) => `data:image/svg+xml,${encodeURIComponent(markup)}`
const FRAME_SRC = svgUri(
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 160 90'><rect width='160' height='90' fill='#dfe4ea'/><circle cx='80' cy='38' r='22' fill='#aeb9c5'/><rect x='54' y='58' width='52' height='36' rx='10' fill='#bcc5d0'/></svg>",
)
const HEAT_SRC = svgUri(
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 160 90'><defs><radialGradient id='a'><stop offset='0' stop-color='#ef4444' stop-opacity='.9'/><stop offset='1' stop-color='#ef4444' stop-opacity='0'/></radialGradient><radialGradient id='b'><stop offset='0' stop-color='#f59e0b' stop-opacity='.85'/><stop offset='1' stop-color='#f59e0b' stop-opacity='0'/></radialGradient></defs><ellipse cx='72' cy='38' rx='34' ry='28' fill='url(#a)'/><ellipse cx='96' cy='56' rx='24' ry='22' fill='url(#b)'/></svg>",
)

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      <div className={styles.canvas}>{children}</div>
    </section>
  )
}

export default function Playground() {
  const [theme, setTheme] = useState<Theme>('system')

  // tokens.css reaguje na [data-theme] na <html>. 'system' = brak atrybutu (decyduje OS).
  useEffect(() => {
    const root = document.documentElement
    if (theme === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', theme)
  }, [theme])

  // Alert jest kontrolowany: X woła onClose, a rodzic (tu) decyduje o ukryciu.
  const [openAlerts, setOpenAlerts] = useState({
    success: true,
    danger: true,
    warning: true,
    info: true,
  })

  function closeAlert(key: keyof typeof openAlerts) {
    setOpenAlerts((prev) => ({ ...prev, [key]: false }))
  }

  function resetAlerts() {
    setOpenAlerts({ success: true, danger: true, warning: true, info: true })
  }

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>UI Playground</h1>
          <p className={styles.subtitle}>
            Podgląd prymitywów z <code>components/ui/</code>. Dodaj sekcję, gdy tworzysz komponent.
          </p>
        </div>

        <div className={styles.themeSwitch} role="group" aria-label="Motyw">
          {(['light', 'system', 'dark'] as const).map((t) => (
            <button
              key={t}
              type="button"
              className={theme === t ? styles.themeButtonActive : styles.themeButton}
              aria-pressed={theme === t}
              onClick={() => setTheme(t)}
            >
              {t}
            </button>
          ))}
        </div>
      </header>

      <main className={styles.grid}>
        {/* ── PRZYKŁADY: tak wpinasz komponent ─────────────────────────── */}
        <Section title="Badge">
          <Badge variant="success" soft={false}>
            REAL
          </Badge>
          <Badge variant="success" soft={true}>
            REAL
          </Badge>
          <Badge variant="danger" soft={false}>
            DANGER
          </Badge>
          <Badge variant="danger" soft={true}>
            DANGER
          </Badge>
          <Badge variant="warning" soft={false}>
            WARNING
          </Badge>
          <Badge variant="warning" soft={true}>
            WARNING
          </Badge>
        </Section>

        <Section title="Spinner">
          <Spinner size="sm" />
          <Spinner size="md" />
          <Spinner size="lg" />
        </Section>

        <Section title="Button">
          <Button variant="primary" size="sm" isLoading={false}>
            Primary small
          </Button>
          <Button variant="primary" size="md" isLoading={false}>
            Primary medium
          </Button>
          <Button variant="primary" size="md" isLoading={false} leftIcon={Check}>
            Primary medium
          </Button>
          <Button variant="primary" size="md" isLoading={false} disabled={true}>
            Primary medium
          </Button>
          <Button variant="primary" size="md" isLoading={true}>
            Primary medium
          </Button>

          <Button variant="danger" size="sm" isLoading={false}>
            Danger small
          </Button>
          <Button variant="danger" size="md" isLoading={false}>
            Danger medium
          </Button>
          <Button variant="danger" size="md" isLoading={false} leftIcon={Check}>
            Danger medium
          </Button>
          <Button variant="danger" size="md" isLoading={false} disabled={true}>
            Danger medium
          </Button>
          <Button variant="danger" size="md" isLoading={true}>
            Danger medium
          </Button>

          <Button variant="ghost" size="sm" isLoading={false}>
            Ghost small
          </Button>
          <Button variant="ghost" size="md" isLoading={false}>
            Ghost medium
          </Button>
          <Button variant="ghost" size="md" isLoading={false} leftIcon={Check}>
            Ghost medium
          </Button>
          <Button variant="ghost" size="md" isLoading={false} disabled={true}>
            Ghost medium
          </Button>
          <Button variant="ghost" size="md" isLoading={true}>
            Ghost medium
          </Button>
        </Section>

        <Section title="LinkButton">
          {/* Wszystkie nawigują do /upload — to <a>, nie <button> (Ctrl+klik, nowa karta, kopiuj link). */}
          <LinkButton to="/upload" variant="primary" size="sm">
            Primary small
          </LinkButton>
          <LinkButton to="/upload" variant="primary" size="md">
            Primary medium
          </LinkButton>
          <LinkButton to="/upload" variant="primary" size="md" leftIcon={Upload}>
            Z ikoną z lewej
          </LinkButton>
          <LinkButton to="/upload" variant="primary" size="md" rightIcon={ArrowRight}>
            Z ikoną z prawej
          </LinkButton>

          <LinkButton to="/upload" variant="danger" size="sm">
            Danger small
          </LinkButton>
          <LinkButton to="/upload" variant="danger" size="md">
            Danger medium
          </LinkButton>
          <LinkButton to="/upload" variant="danger" size="md" leftIcon={Upload}>
            Danger z ikoną
          </LinkButton>

          <LinkButton to="/upload" variant="ghost" size="sm">
            Ghost small
          </LinkButton>
          <LinkButton to="/upload" variant="ghost" size="md">
            Ghost medium
          </LinkButton>
          <LinkButton to="/upload" variant="ghost" size="md" leftIcon={Upload}>
            Ghost z ikoną
          </LinkButton>
        </Section>

        <Section title="Alert">
          <Button variant="ghost" size="sm" onClick={resetAlerts}>
            Przywróć alerty
          </Button>

          {/* Z przyciskiem zamykania (onClose → ukrycie przez stan rodzica) */}
          {openAlerts.success && (
            <Alert
              variant="success"
              title="Analiza zakończona"
              onClose={() => closeAlert('success')}
            >
              Plik oceniony jako autentyczny (REAL).
            </Alert>
          )}
          {openAlerts.danger && (
            <Alert
              variant="danger"
              title="Wykryto manipulację"
              onClose={() => closeAlert('danger')}
            >
              Wysokie prawdopodobieństwo deepfake (FAKE).
            </Alert>
          )}
          {openAlerts.warning && (
            <Alert variant="warning" title="Niska pewność" onClose={() => closeAlert('warning')}>
              Jakość źródła ogranicza wiarygodność wyniku.
            </Alert>
          )}
          {openAlerts.info && (
            <Alert variant="info" title="Wskazówka" onClose={() => closeAlert('info')}>
              Najlepsze wyniki dają pliki ≥ 720p i czysta ścieżka audio.
            </Alert>
          )}

          {/* Bez onClose — przycisk X się nie renderuje (alert nieusuwalny) */}
          <Alert variant="info" title="Bez zamykania">
            Brak propsa onClose, więc nie ma przycisku X.
          </Alert>

          {/* Własna ikona zamiast domyślnej z wariantu */}
          <Alert variant="info" title="Własna ikona" icon={Upload}>
            Ikonę nadpisano propsem <code>icon</code>.
          </Alert>
        </Section>

        <Section title="Input">
          <Input placeholder="Zwykły" />
          <Input placeholder="Aktywny" data-focus="true" />
          <Input placeholder="Błąd" aria-invalid={true} />
        </Section>

        <Section title="FormField">
          {/* id, aria-invalid i aria-describedby wstrzykuje FormField przez kontekst —
              Input nie dostaje ich jawnie. */}
          <FormField label="Adres URL nagrania">
            <Input placeholder="https://…" />
          </FormField>

          <FormField label="Wypełnione pole">
            <Input defaultValue="przyklad.mp4" />
          </FormField>

          <FormField label="Aktywne (focus)">
            <Input data-focus="true" placeholder="ring akcentu" />
          </FormField>

          <FormField label="Pole z błędem" error="Podaj prawidłowy adres URL.">
            <Input defaultValue="ftp://zly-adres" />
          </FormField>

          <FormField label="Wyłączone">
            <Input disabled placeholder="niedostępne" />
          </FormField>
        </Section>

        <Section title="ProgressBar">
          {/* Pasek ma width:100%, więc w canvasie (flex-wrap) każdy bierze cały wiersz.
              Realną szerokość kontrolujesz kontenerem (patrz ostatni przykład). */}
          <ProgressBar label="Pewność: REAL" value={72} showValue tone="accent" />
          <ProgressBar label="Pewność: FAKE" value={94} showValue tone="danger" />
          <ProgressBar label="Upload pliku" value={40} showValue />
          <ProgressBar label="Start (0%)" value={0} showValue />
          <ProgressBar label="Koniec (100%)" value={100} />
          <ProgressBar label="Clamp: value=150 → 100%" value={150} showValue />
          <ProgressBar label="Analiza w toku… (indeterminate)" />

          {/* Szerokość kontroluje rodzic: tu sztuczne 200px */}
          <div style={{ width: 250 }}>
            <ProgressBar label="W kontenerze 200px" value={50} showValue />
          </div>
        </Section>

        <Section title="Card">
          {/* Card jest width:100% — ograniczamy go kontenerem (tu 320px). */}
          <div style={{ width: 320 }}>
            <Card>
              <CardMedia>
                <FrameHeatmap
                  frameSrc={FRAME_SRC}
                  heatmapSrc={HEAT_SRC}
                  alt="Klatka analizowanego nagrania, 00:01:32"
                />
              </CardMedia>
              <CardBody>
                <div className={styles.cardRow}>
                  <strong>interview_clip_04.mp4</strong>
                  <Badge variant="danger">FAKE</Badge>
                </div>
              </CardBody>
              <CardFooter>
                <span className={styles.cardMeta}>video 0.94 · audio 0.88 · 00:01:32</span>
              </CardFooter>
            </Card>
          </div>

          {/* REAL — bez nakładki heatmapy (showHeatmap=false) */}
          <div style={{ width: 320 }}>
            <Card>
              <CardMedia>
                <FrameHeatmap
                  frameSrc={FRAME_SRC}
                  heatmapSrc={HEAT_SRC}
                  showHeatmap={false}
                  alt="Klatka analizowanego nagrania, 00:00:48"
                />
              </CardMedia>
              <CardBody>
                <div className={styles.cardRow}>
                  <strong>press_briefing.mp4</strong>
                  <Badge variant="success">REAL</Badge>
                </div>
              </CardBody>
              <CardFooter>
                <span className={styles.cardMeta}>video 0.08 · audio 0.05 · 00:00:48</span>
              </CardFooter>
            </Card>
          </div>

          {/* Klikalna — hover-lift; realny element interaktywny w środku, nie onClick na karcie */}
          <div style={{ width: 320 }}>
            <Card interactive>
              <CardMedia>
                <FrameHeatmap
                  frameSrc={FRAME_SRC}
                  heatmapSrc={HEAT_SRC}
                  alt="Klatka analizowanego nagrania, 00:02:10"
                />
              </CardMedia>
              <CardBody>
                <div className={styles.cardRow}>
                  <strong>clip_audio_only.mp4</strong>
                  <Badge variant="warning">NISKA PEWNOŚĆ</Badge>
                </div>
              </CardBody>
              <CardFooter>
                <span className={styles.cardMeta}>video 0.08 · audio 0.05 · 00:00:48</span>
              </CardFooter>
            </Card>
          </div>

          {/* Bez obrazu — aspect-ratio trzyma wysokość media (zero CLS) */}
          <div style={{ width: 320 }}>
            <Card>
              <CardMedia>
                <div className={styles.cardPlaceholder}>[ podgląd klatki + Grad-CAM ]</div>
              </CardMedia>
              <CardBody>
                <div className={styles.cardRow}>
                  <strong>Brak klatki</strong>
                </div>
              </CardBody>
            </Card>
          </div>
        </Section>
      </main>
    </div>
  )
}
