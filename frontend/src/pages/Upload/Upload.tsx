import { useEffect, useRef, useState } from 'react'

import { useNavigate } from 'react-router-dom'

import { ACCEPT_ATTR, validateDuration, validateFile } from './upload-utils'
import { useAnalysisFlow } from './use-analysis-flow'
import { Dropzone } from './components/Dropzone'
import { ErrorState } from './components/ErrorState'
import { MediaPreview } from './components/MediaPreview'
import { PreviewState } from './components/PreviewState'
import { UploadingState } from './components/UploadingState'
import { AnalyzingState } from './components/AnalyzingState'
import { FailedState } from './components/FailedState'

import styles from './Upload.module.css'

export default function Upload() {
  const navigate = useNavigate()

  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null) // błąd walidacji KLIENCKIEJ (przed uploadem)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)

  // Współdzielony input — wszystkie widoki delegują tu picking przez openPicker().
  const inputRef = useRef<HTMLInputElement>(null)

  // Cykl analizy (upload → start → SSE → wynik). Na COMPLETED nawigujemy do wyniku.
  const flow = useAnalysisFlow((analysisId) => {
    navigate(`/analysis-result/${analysisId}`)
  })

  // Object URL żyje tyle co podgląd — revoke przy podmianie pliku i przy odmontowaniu.
  useEffect(() => {
    if (!previewUrl) return
    return () => URL.revokeObjectURL(previewUrl)
  }, [previewUrl])

  // Zamknięcie karty w trakcie wysyłki przerywa upload — przeglądarka pyta o potwierdzenie.
  const uploading = flow.state.name === 'uploading'
  useEffect(() => {
    if (!uploading) return
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault()
      event.returnValue = '' // starsze przeglądarki wymagają ustawienia returnValue
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [uploading])

  function openPicker() {
    inputRef.current?.click()
  }

  function reset() {
    setFile(null)
    setError(null)
    setPreviewUrl(null)
    flow.retry() // wróć do idle, gdyby cykl był w stanie failed
    if (inputRef.current) inputRef.current.value = '' // pozwala wybrać ten sam plik ponownie
  }

  function rejectFile(message: string) {
    setError(message)
    setFile(null)
    setPreviewUrl(null)
    if (inputRef.current) inputRef.current.value = ''
  }

  // Długość znamy dopiero z metadanych podglądu. Odrzucamy tylko przed startem — w trakcie uploadu
  // rozstrzyga backend (422 z tym samym komunikatem).
  function handleDuration(seconds: number) {
    const message = validateDuration(seconds)
    if (message && flow.state.name === 'idle') rejectFile(message)
  }

  function handleFiles(files: FileList | null) {
    const picked = files?.[0]
    if (!picked) return

    const message = validateFile(picked)
    if (message) return rejectFile(message)

    setError(null)
    setFile(picked)
    setPreviewUrl(URL.createObjectURL(picked))
  }

  return (
    <div className={styles.page}>
      <header className={styles.head}>
        <h1>Nowa analiza</h1>
        <p className={styles.subtitle}>
          Wgraj plik wideo lub audio — sprawdzimy autentyczność obrazu i dźwięku.
        </p>
      </header>

      {/* Ukryty input — wspólny dla wszystkich stanów, sterowany refem. */}
      <input
        ref={inputRef}
        type="file"
        accept={ACCEPT_ATTR}
        hidden
        onChange={(e) => handleFiles(e.target.files)}
      />

      {error ? (
        <ErrorState message={error} onPick={openPicker} />
      ) : file && previewUrl ? (
        renderFlow(file, previewUrl)
      ) : (
        <Dropzone onFiles={handleFiles} onPick={openPicker} />
      )}
    </div>
  )

  // Gałąź „plik wybrany" → widok zależny od fazy cyklu analizy.
  function renderFlow(file: File, previewUrl: string) {
    // Błąd analizy ma osobną (czerwoną) kartę bez media.
    if (flow.state.name === 'failed') {
      return <FailedState message={flow.state.message} onRetry={flow.retry} />
    }

    // idle/uploading/starting/analyzing dzielą JEDNĄ kartę ze STABILNYM <video> — wymienia się tylko
    // stopka/sekcja postępu pod spodem. Dzięki temu film nie przeładowuje się przy zmianie stanu.
    return (
      <div className={styles.previewCard}>
        <MediaPreview file={file} previewUrl={previewUrl} onDuration={handleDuration} />
        {renderPhaseControls(file)}
      </div>
    )
  }

  function renderPhaseControls(file: File) {
    switch (flow.state.name) {
      case 'uploading':
        return <UploadingState progress={flow.uploadProgress} onCancel={flow.cancel} />
      case 'starting':
        // analiza jeszcze nie ma id → bez „Anuluj"; paski nieokreślone (bySource pusty)
        return (
          <AnalyzingState file={file} analysisType={flow.analysisType} bySource={flow.bySource} />
        )
      case 'analyzing':
        return (
          <AnalyzingState
            file={file}
            analysisType={flow.analysisType}
            bySource={flow.bySource}
            onCancel={flow.cancel}
          />
        )
      default: // 'idle'
        return <PreviewState onReset={reset} onAnalyze={() => flow.start(file)} />
    }
  }
}
