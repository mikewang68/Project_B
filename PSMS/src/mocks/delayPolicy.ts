import { delay } from 'msw';

export type DelayExecutor = (milliseconds: number) => Promise<void>;

const defaultDelayExecutor: DelayExecutor = async (milliseconds) => {
  await delay(milliseconds);
};

let delayExecutor: DelayExecutor = defaultDelayExecutor;

export function setDelayExecutor(executor: DelayExecutor): void {
  delayExecutor = executor;
}

export function resetDelayExecutor(): void {
  delayExecutor = defaultDelayExecutor;
}

export async function waitForMockDelay(milliseconds: number): Promise<void> {
  await delayExecutor(milliseconds);
}
