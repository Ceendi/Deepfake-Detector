import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/errors'

import { ReportPdfButton } from './ReportPdfButton'

vi.mock('@/api/analysis', () => ({ fetchReportPdf: vi.fn() }))

import { fetchReportPdf } from '@/api/analysis'

const createObjectURL = vi.fn(() => 'blob:report')
const revokeObjectURL = vi.fn()

beforeEach(() => {
  vi.resetAllMocks()
  createObjectURL.mockReturnValue('blob:report')
  Object.assign(URL, { createObjectURL, revokeObjectURL })
})

describe('ReportPdfButton', () => {
  it('pobiera PDF z tokenem jako blob, klika <a download> i zwalnia URL', async () => {
    const blob = new Blob(['%PDF'], { type: 'application/pdf' })
    vi.mocked(fetchReportPdf).mockResolvedValue({ blob, filename: 'report-an-1.pdf' })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    render(<ReportPdfButton analysisId="an-1" status="COMPLETED" variant="primary" label="PDF" />)
    fireEvent.click(screen.getByRole('button', { name: 'PDF' }))

    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect(fetchReportPdf).toHaveBeenCalledWith('an-1', expect.any(AbortSignal))
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    const link = click.mock.contexts[0] as HTMLAnchorElement
    expect(link.download).toBe('report-an-1.pdf')
    expect(link.href).toBe('blob:report')

    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:report'))
    click.mockRestore()
  })

  it('409 → „Raport jeszcze niegotowy”', async () => {
    vi.mocked(fetchReportPdf).mockRejectedValue(new ApiError({ status: 409, message: 'x' }))

    render(<ReportPdfButton analysisId="an-1" status="COMPLETED" variant="primary" label="PDF" />)
    fireEvent.click(screen.getByRole('button', { name: 'PDF' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Raport jeszcze niegotowy.')
  })

  it('dla analizy w toku przycisk jest wyłączony', () => {
    render(<ReportPdfButton analysisId="an-1" status="PROCESSING" variant="ghost" label="PDF" />)
    expect(screen.getByRole('button', { name: 'PDF' })).toBeDisabled()
  })
})
