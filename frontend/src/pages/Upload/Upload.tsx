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
  const [error, setError] = useState<string | null>(null)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)

  const inputRef = useRef<HTMLInputElement>(null)

  const flow = useAnalysisFlow((analysisId) => {
    navigate(`/analysis-result/${analysisId}`)
  })

  useEffect(() => {
    if (!previewUrl) return
    return () => URL.revokeObjectURL(previewUrl)
  }, [previewUrl])

  const uploading = flow.state.name === 'uploading'
  useEffect(() => {
    if (!uploading) return
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [uploading])

  useEffect(() => {
    const preventFileNavigation = (event: DragEvent) => {
      if (event.dataTransfer?.types.includes('Files')) event.preventDefault()
    }
    window.addEventListener('dragover', preventFileNavigation)
    window.addEventListener('drop', preventFileNavigation)
    return () => {
      window.removeEventListener('dragover', preventFileNavigation)
      window.removeEventListener('drop', preventFileNavigation)
    }
  }, [])

  function openPicker() {
    inputRef.current?.click()
  }

  function reset() {
    setFile(null)
    setError(null)
    setPreviewUrl(null)
    flow.reset()
    if (inputRef.current) inputRef.current.value = ''
  }

  function rejectFile(message: string) {
    setError(message)
    setFile(null)
    setPreviewUrl(null)
    if (inputRef.current) inputRef.current.value = ''
  }

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

  function renderFlow(file: File, previewUrl: string) {
    if (flow.state.name === 'failed') {
      return <FailedState message={flow.state.message} onRetry={flow.retry} />
    }

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
        return (
          <AnalyzingState file={file} analysisType={flow.analysisType} bySource={flow.bySource} />
        )
      case 'analyzing':
      case 'stillRunning': {
        const stillRunning = flow.state.name === 'stillRunning' ? flow.state : undefined

        const watching = !stillRunning || stillRunning.watching
        return (
          <AnalyzingState
            file={file}
            analysisType={flow.analysisType}
            bySource={flow.bySource}
            onCancel={watching ? flow.cancel : undefined}
            cancelling={flow.cancelling}
            cancelError={flow.cancelError}
            stillRunningId={stillRunning?.analysisId}
            watching={watching}
            timedOut={stillRunning?.timedOut}
          />
        )
      }
      default: // 'idle'
        return <PreviewState onReset={reset} onAnalyze={() => flow.start(file)} />
    }
  }
}
