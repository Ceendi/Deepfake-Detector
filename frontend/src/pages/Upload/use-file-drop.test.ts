import { describe, it, expect, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import type { DragEvent } from 'react'

import { useFileDrop } from './use-file-drop'

function dragEvent(files: File[] = []): DragEvent {
  return {
    preventDefault: vi.fn(),
    dataTransfer: { files: files as unknown as FileList, dropEffect: '' },
  } as unknown as DragEvent
}

describe('useFileDrop', () => {
  it('startuje bez przeciągania', () => {
    const { result } = renderHook(() => useFileDrop(vi.fn()))
    expect(result.current.isDragging).toBe(false)
  })

  it('dragEnter włącza isDragging', () => {
    const { result } = renderHook(() => useFileDrop(vi.fn()))
    act(() => result.current.dropHandlers.onDragEnter(dragEvent()))
    expect(result.current.isDragging).toBe(true)
  })

  it('zagnieżdżony enter/leave nie miga (zostaje true dopóki głębokość > 0)', () => {
    const { result } = renderHook(() => useFileDrop(vi.fn()))
    act(() => result.current.dropHandlers.onDragEnter(dragEvent())) // depth 1
    act(() => result.current.dropHandlers.onDragEnter(dragEvent()))
    act(() => result.current.dropHandlers.onDragLeave(dragEvent()))
    expect(result.current.isDragging).toBe(true)
  })

  it('powrót głębokości do zera wyłącza isDragging', () => {
    const { result } = renderHook(() => useFileDrop(vi.fn()))
    act(() => result.current.dropHandlers.onDragEnter(dragEvent()))
    act(() => result.current.dropHandlers.onDragLeave(dragEvent()))
    expect(result.current.isDragging).toBe(false)
  })

  it('onDragOver ustawia dropEffect=copy i blokuje domyślną nawigację', () => {
    const { result } = renderHook(() => useFileDrop(vi.fn()))
    const e = dragEvent()
    act(() => result.current.dropHandlers.onDragOver(e))
    expect(e.preventDefault).toHaveBeenCalled()
    expect(e.dataTransfer.dropEffect).toBe('copy')
  })

  it('onDrop przekazuje pliki i resetuje isDragging', () => {
    const onFiles = vi.fn()
    const { result } = renderHook(() => useFileDrop(onFiles))
    const file = new File(['x'], 'clip.mp4', { type: 'video/mp4' })

    act(() => result.current.dropHandlers.onDragEnter(dragEvent([file])))
    act(() => result.current.dropHandlers.onDrop(dragEvent([file])))

    expect(onFiles).toHaveBeenCalledOnce()
    expect(onFiles.mock.calls[0][0]).toHaveLength(1)
    expect(result.current.isDragging).toBe(false)
  })
})
