import { Upload as UploadIcon } from 'lucide-react'
import { clsx } from 'clsx'

import { Button } from '@/components/ui/Button/Button'

import { useFileDrop } from '../use-file-drop'
import styles from '../Upload.module.css'

interface DropzoneProps {
  onFiles: (files: FileList | null) => void
  onPick: () => void
}

// Strefa drag&drop (stan idle). Drag UX trzyma w sobie (hook), picking deleguje propsem
// do współdzielonego <input> w rodzicu.
export function Dropzone({ onFiles, onPick }: DropzoneProps) {
  const { isDragging, dropHandlers } = useFileDrop(onFiles)

  return (
    <div className={clsx(styles.dropzone, isDragging && styles.dragging)} {...dropHandlers}>
      <div className={styles.iconTile}>
        <UploadIcon size={36} strokeWidth={2} aria-hidden="true" />
      </div>

      <h2 className={styles.dzTitle}>
        {isDragging ? 'Upuść plik, aby dodać' : 'Przeciągnij plik wideo lub audio'}
      </h2>
      <p className={styles.dzHint}>
        {isDragging ? 'zwolnij przycisk myszy' : 'albo wybierz go z dysku'}
      </p>

      <Button
        variant="primary"
        size="md"
        className={styles.pickButton}
        leftIcon={UploadIcon}
        onClick={onPick}
      >
        Wybierz plik
      </Button>

      <hr className={styles.divider} />
      <p className={styles.formats}>
        <span className={styles.formatsLabel}>max </span>
        <span className={styles.formatsValue}>500 MB</span>
        <span className={styles.formatsLabel}> · Video: </span>
        <span className={styles.formatsValue}>MP4 · MOV · WEBM</span>
        <span className={styles.formatsLabel}> · Audio: </span>
        <span className={styles.formatsValue}>WAV · MP3 · FLAC</span>
      </p>
    </div>
  )
}
