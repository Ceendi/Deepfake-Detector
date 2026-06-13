import { useRef, useState } from 'react'

/**
 * Enkapsuluje UX przeciągania pliku na strefę drop.
 *
 * dragenter/dragleave odpalają się też dla dzieci kontenera, więc gołe set(true/false)
 * miga. Trzymamy licznik wejść/wyjść w ref (zmiana licznika nie ma re-renderować) —
 * re-render robi dopiero pochodny boolean `isDragging`.
 *
 * Zwraca `dropHandlers` do rozłożenia na element strefy oraz `isDragging` do stylowania.
 */
export function useFileDrop(onFiles: (files: FileList | null) => void) {
  const [isDragging, setIsDragging] = useState(false)
  const depth = useRef(0)

  const dropHandlers = {
    onDragEnter(e: React.DragEvent) {
      e.preventDefault()
      depth.current += 1
      if (depth.current === 1) setIsDragging(true)
    },
    onDragOver(e: React.DragEvent) {
      // KLUCZOWE: bez preventDefault przeglądarka otworzy plik jako nawigację i drop nie odpali.
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
    },
    onDragLeave(e: React.DragEvent) {
      e.preventDefault()
      depth.current -= 1
      if (depth.current <= 0) {
        depth.current = 0
        setIsDragging(false)
      }
    },
    onDrop(e: React.DragEvent) {
      e.preventDefault()
      depth.current = 0
      setIsDragging(false)
      onFiles(e.dataTransfer.files)
    },
  }

  return { isDragging, dropHandlers }
}
