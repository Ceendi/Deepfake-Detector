import { useState } from 'react'

import { Video as VideoIcon, Music as MusicIcon } from 'lucide-react'
import { clsx } from 'clsx'

import { AudioPlayer } from '@/components/ui/AudioPlayer/AudioPlayer'
import { VideoPlayer } from '@/components/ui/VideoPlayer/VideoPlayer'

import { isVideoFile, getExt, formatMB, formatDuration } from '../upload-utils'
import styles from '../Upload.module.css'

export function MediaPreview({
  file,
  previewUrl,
  onDuration,
}: {
  file: File
  previewUrl: string
  onDuration?: (seconds: number) => void
}) {
  const [duration, setDurationState] = useState<number | null>(null)

  const [previewFailed, setPreviewFailed] = useState(false)
  function setDuration(seconds: number) {
    setDurationState(seconds)
    onDuration?.(seconds)
  }
  const isVideo = isVideoFile(file)
  const ext = getExt(file.name).toUpperCase()

  const meta = [
    `${formatMB(file.size)} MB`,
    file.type || (isVideo ? 'video' : 'audio'),
    duration != null ? formatDuration(duration) : null,
  ]
    .filter(Boolean)
    .join('  ·  ')

  return (
    <>
      <div className={clsx(styles.media, !isVideo && styles.mediaAudio)}>
        {previewFailed ? (
          <p className={styles.previewUnavailable}>
            Podgląd niedostępny dla tego formatu w przeglądarce. Długość pliku sprawdzi serwer.
          </p>
        ) : isVideo ? (
          <VideoPlayer
            src={previewUrl}
            onDuration={setDuration}
            onError={() => setPreviewFailed(true)}
          />
        ) : (
          <AudioPlayer
            className={styles.audio}
            src={previewUrl}
            onDuration={setDuration}
            onError={() => setPreviewFailed(true)}
          />
        )}
      </div>

      <div className={styles.fileRow}>
        <div className={styles.fileIcon}>
          {isVideo ? (
            <VideoIcon size={20} strokeWidth={2} aria-hidden="true" />
          ) : (
            <MusicIcon size={20} strokeWidth={2} aria-hidden="true" />
          )}
        </div>
        <div className={styles.fileMain}>
          <div className={styles.fileName} title={file.name}>
            {file.name}
          </div>
          <div className={styles.fileMeta}>{meta}</div>
        </div>
        {ext && <span className={styles.formatChip}>{ext}</span>}
      </div>
    </>
  )
}
