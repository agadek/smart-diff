import { Clock } from "./clock";
import { Metrics } from "./metrics";

export class Timer {
  private started = 0;

  start(clock: Clock): void {
    this.started = clock.now();
  }

  elapsed(clock: Clock): number {
    return clock.now() - this.started;
  }
}
