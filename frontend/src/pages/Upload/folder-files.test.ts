import { describe, expect, it } from 'vitest'

import { droppedFolderEntries, filesFromEntries, keepMedia } from './folder-files'

function fileEntry(name: string, readable = true): FileSystemFileEntry {
  return {
    isFile: true,
    isDirectory: false,
    name,
    file: (ok: (file: File) => void, fail?: (error: DOMException) => void) =>
      readable ? ok(new File(['x'], name)) : fail?.(new DOMException('denied', 'NotReadableError')),
  } as unknown as FileSystemFileEntry
}

// Hands out children in batches of two, like the browser's paged readEntries.
function dirEntry(name: string, children: FileSystemEntry[]): FileSystemDirectoryEntry {
  return {
    isFile: false,
    isDirectory: true,
    name,
    createReader: () => {
      let offset = 0
      return {
        readEntries: (ok: (entries: FileSystemEntry[]) => void) => {
          ok(children.slice(offset, offset + 2))
          offset += 2
        },
      }
    },
  } as unknown as FileSystemDirectoryEntry
}

describe('filesFromEntries', () => {
  it('walks nested folders across paged listings and skips unreadable files', async () => {
    const tree = dirEntry('nagrania', [
      fileEntry('a.mp4'),
      fileEntry('.DS_Store'),
      fileEntry('locked.wav', false),
      dirEntry('wywiady', [fileEntry('b.wav'), dirEntry('pusty', [])]),
      fileEntry('c.mov'),
    ])

    const files = await filesFromEntries([tree, fileEntry('luzem.mp3')])

    expect(files.map((file) => file.name)).toEqual([
      'a.mp4',
      '.DS_Store',
      'b.wav',
      'c.mov',
      'luzem.mp3',
    ])
  })
})

describe('keepMedia', () => {
  it('keeps supported media and counts the rest as skipped', () => {
    const names = ['a.mp4', '.DS_Store', 'opis.txt', 'b.WAV']
    const { files, skipped } = keepMedia(names.map((name) => new File(['x'], name)))
    expect(files.map((file) => file.name)).toEqual(['a.mp4', 'b.WAV'])
    expect(skipped).toBe(2)
  })
})

describe('droppedFolderEntries', () => {
  const transfer = (entries: (FileSystemEntry | null)[]) =>
    ({
      items: entries.map((entry) => ({ webkitGetAsEntry: () => entry })),
    }) as unknown as DataTransfer

  it('returns entries only when a folder was dropped', () => {
    const folder = dirEntry('nagrania', [])
    expect(droppedFolderEntries(transfer([fileEntry('a.mp4')]))).toBeNull()
    expect(droppedFolderEntries(transfer([folder, null, fileEntry('b.wav')]))).toHaveLength(2)
  })
})
