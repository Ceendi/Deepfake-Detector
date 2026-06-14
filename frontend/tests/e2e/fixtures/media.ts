// Generatory plików testowych in-memory — nie commitujemy binariów mediów (CLAUDE.md).
// Playwright `setInputFiles({ name, mimeType, buffer })` przyjmuje payload z pamięci, więc
// nie potrzebujemy plików na dysku ani natywnego okna wyboru.

// Minimalny, POPRAWNY kontener WAV (PCM 16-bit, mono, 16 kHz). Przechodzi walidację File Service:
// Tika wykryje audio/x-wav (magic bytes "RIFF…WAVE"), a ffprobe rozpozna format „wav".
// To wystarcza do testu ścieżki upload→start (realny detektor i tak nie wyda sensownego
// werdyktu z ~sekundy dźwięku — werdykt testujemy osobno, patrz tests/e2e/README.md).
export function makeWavFile(seconds = 1, sampleRate = 16_000): Buffer {
  const numSamples = Math.floor(seconds * sampleRate)
  const dataSize = numSamples * 2 // 16-bit mono
  const buf = Buffer.alloc(44 + dataSize)

  // --- RIFF header ---
  buf.write('RIFF', 0, 'ascii')
  buf.writeUInt32LE(36 + dataSize, 4) // ChunkSize
  buf.write('WAVE', 8, 'ascii')

  // --- fmt subchunk ---
  buf.write('fmt ', 12, 'ascii')
  buf.writeUInt32LE(16, 16) // Subchunk1Size (PCM)
  buf.writeUInt16LE(1, 20) // AudioFormat = PCM
  buf.writeUInt16LE(1, 22) // NumChannels = mono
  buf.writeUInt32LE(sampleRate, 24)
  buf.writeUInt32LE(sampleRate * 2, 28) // ByteRate = sampleRate * blockAlign
  buf.writeUInt16LE(2, 32) // BlockAlign = channels * bytesPerSample
  buf.writeUInt16LE(16, 34) // BitsPerSample

  // --- data subchunk ---
  buf.write('data', 36, 'ascii')
  buf.writeUInt32LE(dataSize, 40)

  // Cichy ton 220 Hz — odrobina sygnału zamiast czystej ciszy (nieszkodliwe dla walidacji).
  for (let i = 0; i < numSamples; i++) {
    const sample = Math.round(Math.sin((2 * Math.PI * 220 * i) / sampleRate) * 1500)
    buf.writeInt16LE(sample, 44 + i * 2)
  }

  return buf
}

// Payload gotowy do `locator.setInputFiles(...)`.
export function wavPayload(name = 'sample.wav') {
  return { name, mimeType: 'audio/wav', buffer: makeWavFile() }
}
