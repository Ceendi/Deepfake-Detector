import { FolderOpen, Upload as UploadIcon } from 'lucide-react'
import { clsx } from 'clsx'

import { Button } from '@/components/ui/Button/Button'

import { AUDIO_FORMATS_LABEL, VIDEO_FORMATS_LABEL } from '../upload-utils'
import { useFileDrop, type FilesHandler } from '../use-file-drop'
import styles from '../Upload.module.css'

interface DropzoneProps {
  onFiles: FilesHandler
  onPick: () => void
  onPickFolder: () => void
}

export function Dropzone({ onFiles, onPick, onPickFolder }: DropzoneProps) {
  const { isDragging, dropHandlers } = useFileDrop(onFiles)

  return (
    <div className={clsx(styles.dropzone, isDragging && styles.dragging)} {...dropHandlers}>
      <div className={styles.iconTile}>
        <UploadIcon size={36} strokeWidth={2} aria-hidden="true" />
      </div>

      <h2 className={styles.dzTitle}>
        {isDragging ? 'Upuść, aby dodać' : 'Przeciągnij pliki wideo lub audio albo folder'}
      </h2>
      <p className={styles.dzHint}>
        {isDragging ? 'zwolnij przycisk myszy' : 'albo wybierz je z dysku'}
      </p>

      <div className={styles.pickActions}>
        <Button
          variant="primary"
          size="md"
          className={styles.pickButton}
          leftIcon={UploadIcon}
          onClick={onPick}
        >
          Wybierz pliki
        </Button>
        <Button
          variant="ghost"
          size="md"
          className={styles.pickButton}
          leftIcon={FolderOpen}
          onClick={onPickFolder}
        >
          Wybierz folder
        </Button>
      </div>

      <hr className={styles.divider} />
      <p className={styles.formats}>
        <span className={styles.formatsLabel}>max </span>
        <span className={styles.formatsValue}>500 MB</span>
        <span className={styles.formatsLabel}> · Video: </span>
        <span className={styles.formatsValue}>{VIDEO_FORMATS_LABEL}</span>
        <span className={styles.formatsLabel}> · Audio: </span>
        <span className={styles.formatsValue}>{AUDIO_FORMATS_LABEL}</span>
      </p>
    </div>
  )
}
