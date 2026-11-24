import { Clock } from "./clock";

export class Timer {
  private started = 0;

  start(clock: Clock): void {
    this.started = clock.now();
  }
}
