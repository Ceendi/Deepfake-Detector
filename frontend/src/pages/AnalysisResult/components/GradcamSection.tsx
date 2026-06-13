import type { AnalysisDetails } from '@/api/types'
import { Card, CardBody } from '@/components/ui/Card/Card'
import { Spinner } from '@/components/ui/Spinner/Spinner'

import { useGradcamImages } from '../use-gradcam-images'

import styles from '../AnalysisResult.module.css'

// Tylko klatki wideo — gradcam audio jest błędny i ma osobną wizualizację (oś czasu ryzyka audio).
export function GradcamSection({ details }: { details: AnalysisDetails | null }) {
  const urls = details?.video?.gradcamUrls ?? []
  const images = useGradcamImages(urls)

  if (urls.length === 0) return null

  return (
    <section className={styles.section}>
      <span className={styles.sectionLabel}>Grad-CAM — mapa uwagi modelu</span>
      <Card>
        <CardBody>
          <p className={styles.blockText}>
            Mapa cieplna pokazuje obszary klatki, które najmocniej wpłynęły na decyzję modelu wideo.
            Ciepłe kolory (żółty–czerwony) oznaczają wysoki wpływ.
          </p>

          <div className={styles.legend}>
            <span className={styles.legendText}>niski wpływ</span>
            <span className={styles.legendBar} aria-hidden="true" />
            <span className={styles.legendText}>wysoki wpływ</span>
          </div>

          <div className={styles.gradcamGrid}>
            {urls.map((url, i) => {
              const img = images[i]
              return (
                <figure className={styles.frame} key={url}>
                  {img?.status === 'ready' && img.objectUrl ? (
                    <img
                      className={styles.frameImg}
                      src={img.objectUrl}
                      alt={`Grad-CAM klatka ${i + 1}`}
                    />
                  ) : img?.status === 'error' ? (
                    <span className={styles.frameNote}>nie udało się wczytać</span>
                  ) : (
                    <Spinner size="sm" />
                  )}

                  <figcaption className={styles.frameTagStart}>#{i + 1}</figcaption>
                </figure>
              )
            })}
          </div>
        </CardBody>
      </Card>
    </section>
  )
}
