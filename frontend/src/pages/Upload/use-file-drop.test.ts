import { describe, it, expect, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
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

  it('onDrop z folderem przekazuje pliki z całego drzewa', async () => {
    const onFiles = vi.fn()
    const { result } = renderHook(() => useFileDrop(onFiles))
    const folder = {
      isFile: false,
      isDirectory: true,
      createReader: () => {
        let read = false
        return {
          readEntries: (ok: (entries: unknown[]) => void) => {
            ok(
              read
                ? []
                : [
                    {
                      isFile: true,
                      isDirectory: false,
                      file: (cb: (f: File) => void) => cb(new File(['x'], 'a.mp4')),
                    },
                  ],
            )
            read = true
          },
        }
      },
    }
    const e = {
      preventDefault: vi.fn(),
      dataTransfer: { files: [], items: [{ webkitGetAsEntry: () => folder }] },
    } as unknown as DragEvent

    act(() => result.current.dropHandlers.onDrop(e))

    await waitFor(() => expect(onFiles).toHaveBeenCalledOnce())
    const [files, fromFolder] = onFiles.mock.calls[0]
    expect(files.map((f: File) => f.name)).toEqual(['a.mp4'])
    expect(fromFolder).toBe(true)
  })
})
