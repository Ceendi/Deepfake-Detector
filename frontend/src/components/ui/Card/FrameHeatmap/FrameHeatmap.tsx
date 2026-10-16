import styles from './FrameHeatmap.module.css'

interface FrameHeatmapProps {
  frameSrc: string
  alt: string
  heatmapSrc?: string
  showHeatmap?: boolean
}

export function FrameHeatmap({ frameSrc, alt, heatmapSrc, showHeatmap = true }: FrameHeatmapProps) {
  return (
    <>
      <img className={styles.frame} src={frameSrc} alt={alt} />
      {heatmapSrc && showHeatmap && (
        <img className={styles.heat} src={heatmapSrc} alt="" aria-hidden="true" />
      )}
    </>
  )
}
