import { useState } from 'react'

import { Video as VideoIcon, Music as MusicIcon } from 'lucide-react'
import { clsx } from 'clsx'

import { isVideoFile, getExt, formatMB, formatDuration } from '../upload-utils'
import styles from '../Upload.module.css'

// Wspólny podgląd pliku (media + wiersz metadanych) — używany w Preview/Uploading/Analyzing.
// Sam czyta długość mediów z onLoadedMetadata, żeby nie przeciągać duration przez całą stronę.
export function MediaPreview({ file, previewUrl }: { file: File; previewUrl: string }) {
  const [duration, setDuration] = useState<number | null>(null)
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
        {isVideo ? (
          <video
            className={styles.video}
            src={previewUrl}
            controls
            onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
          />
        ) : (
          <audio
            className={styles.audio}
            src={previewUrl}
            controls
            onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
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
