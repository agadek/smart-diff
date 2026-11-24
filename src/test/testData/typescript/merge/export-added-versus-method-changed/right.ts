import { Clock } from "./clock";

class Timer {
  private started = 0;

  start(clock: Clock): void {
    this.started = clock.now();
  }

  elapsed(clock: Clock): number {
    return Math.round(clock.now() - this.started);
  }
}
