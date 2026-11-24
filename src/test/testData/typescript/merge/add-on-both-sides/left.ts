import { Clock } from "./clock";

export class Timer {
  private started = 0;

  start(clock: Clock): void {
    this.started = clock.now();
  }

  reset(): void {
    this.started = 0;
  }

  elapsed(clock: Clock): number {
    return clock.now() - this.started;
  }
}
