export class DeterministicClock {
  private current: string;

  constructor(initialValue: string) {
    this.current = initialValue;
  }

  now(): string {
    return this.current;
  }

  reset(value: string): void {
    this.current = value;
  }
}

export class EnvelopeIdSequence {
  private traceCounter: number;
  private auditCounter: number;

  constructor(traceCounter = 1, auditCounter = 1) {
    this.traceCounter = traceCounter;
    this.auditCounter = auditCounter;
  }

  next(): { traceId: string; auditLogId: string } {
    const ids = {
      traceId: `TRACE-${String(this.traceCounter).padStart(4, '0')}`,
      auditLogId: `AUD-${String(this.auditCounter).padStart(4, '0')}`,
    };
    this.traceCounter += 1;
    this.auditCounter += 1;
    return ids;
  }

  reset(traceCounter = 1, auditCounter = 1): void {
    this.traceCounter = traceCounter;
    this.auditCounter = auditCounter;
  }
}
