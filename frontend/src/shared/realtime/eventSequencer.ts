export interface SequencedEvent {
  sequence: number
  type: string
  payload: unknown
}

export interface SequencedSnapshot {
  sequence: number
}

export class EventSequencer<TSnapshot extends SequencedSnapshot = SequencedSnapshot> {
  private sequence: number
  private pending: Promise<void> = Promise.resolve()

  constructor(initialSequence: number, private readonly reload: () => Promise<TSnapshot>) {
    this.sequence = initialSequence
  }

  get current(): number {
    return this.sequence
  }

  reset(sequence: number): void {
    this.sequence = sequence
  }

  accept(event: SequencedEvent, apply: (event: SequencedEvent) => void = () => undefined): Promise<void> {
    const operation = this.pending.then(() => this.acceptOne(event, apply))
    this.pending = operation.catch(() => undefined)
    return operation
  }

  private async acceptOne(event: SequencedEvent, apply: (event: SequencedEvent) => void): Promise<void> {
    if (!Number.isSafeInteger(event.sequence) || event.sequence <= this.sequence) return
    if (event.sequence > this.sequence + 1) {
      const snapshot = await this.reload()
      this.sequence = snapshot.sequence
      if (event.sequence <= this.sequence) return
    }
    if (event.sequence !== this.sequence + 1) return
    apply(event)
    this.sequence = event.sequence
  }
}
