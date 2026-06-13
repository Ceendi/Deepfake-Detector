import styles from './Card.module.css'

import type { ComponentPropsWithoutRef } from 'react'

import { clsx } from 'clsx'

interface CardProps extends ComponentPropsWithoutRef<'div'> {
  interactive?: boolean
}

export function Card({ interactive = false, className, ...rest }: CardProps) {
  return (
    <div {...rest} className={clsx(styles.card, interactive && styles.interactive, className)} />
  )
}

export function CardMedia({ className, ...rest }: ComponentPropsWithoutRef<'div'>) {
  return <div {...rest} className={clsx(styles.media, className)} />
}

export function CardBody({ className, ...rest }: ComponentPropsWithoutRef<'div'>) {
  return <div {...rest} className={clsx(styles.body, className)} />
}

export function CardFooter({ className, ...rest }: ComponentPropsWithoutRef<'div'>) {
  return <div {...rest} className={clsx(styles.footer, className)} />
}
