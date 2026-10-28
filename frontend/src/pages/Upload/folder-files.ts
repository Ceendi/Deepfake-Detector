import { hasSupportedExt } from './upload-utils'

export interface PickedFiles {
  files: File[]
  skipped: string[] // paths inside the picked folder, or names
}

// Folders hold anything (.DS_Store, documents), so only supported media are queued.
export function keepMedia(files: File[]): PickedFiles {
  const media: File[] = []
  const skipped: string[] = []
  for (const file of files) {
    if (hasSupportedExt(file.name)) media.push(file)
    else skipped.push(file.webkitRelativePath || file.name)
  }
  return { files: media, skipped }
}

function readBatch(reader: FileSystemDirectoryReader): Promise<FileSystemEntry[]> {
  return new Promise((resolve, reject) => reader.readEntries(resolve, reject))
}

function readFile(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject))
}

async function walk(entry: FileSystemEntry, out: File[]): Promise<void> {
  if (entry.isFile) {
    // An unreadable file (permissions, removed meanwhile) must not drop the whole folder.
    const file = await readFile(entry as FileSystemFileEntry).catch(() => null)
    if (file) out.push(file)
    return
  }
  if (!entry.isDirectory) return
  const reader = (entry as FileSystemDirectoryEntry).createReader()
  // readEntries returns at most ~100 entries per call; an empty batch ends the listing.
  for (;;) {
    const batch = await readBatch(reader).catch(() => [])
    if (batch.length === 0) return
    for (const child of batch) await walk(child, out)
  }
}

/** Files of dropped entries, descending into folders recursively. */
export async function filesFromEntries(entries: FileSystemEntry[]): Promise<File[]> {
  const out: File[] = []
  for (const entry of entries) await walk(entry, out)
  return out
}

/** Entries must be taken during the drop event; the data transfer is emptied afterwards. */
export function droppedFolderEntries(dataTransfer: DataTransfer): FileSystemEntry[] | null {
  const entries = Array.from(dataTransfer.items ?? [], (item) => item.webkitGetAsEntry?.() ?? null)
  return entries.some((entry) => entry?.isDirectory)
    ? entries.filter((entry): entry is FileSystemEntry => entry !== null)
    : null
}
