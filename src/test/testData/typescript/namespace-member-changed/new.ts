export namespace Geometry {
  export const PI = Math.PI;

  export function circle(radius: number): number {
    return PI * radius * radius;
  }
}

declare module "legacy-lib" {
  export function init(options?: object): void;
}
