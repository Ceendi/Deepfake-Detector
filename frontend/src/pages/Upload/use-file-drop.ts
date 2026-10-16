import { useRef, useState } from 'react'

export function useFileDrop(onFiles: (files: FileList | null) => void) {
  const [isDragging, setIsDragging] = useState(false)
  // Child elements also emit enter/leave, so track depth to avoid flickering.
  const depth = useRef(0)

  const dropHandlers = {
    onDragEnter(e: React.DragEvent) {
      e.preventDefault()
      depth.current += 1
      if (depth.current === 1) setIsDragging(true)
    },
    onDragOver(e: React.DragEvent) {
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
