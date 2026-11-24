import { Clock } from "./clock";

export class Timer {
  private started = 0;

  start(clock: Clock): void {
    this.started = clock.now();
  }

  stop(clock: Clock): number {
    return this.elapsed(clock);
  }

  elapsed(clock: Clock): number {
    return clock.now() - this.started;
  }
}
