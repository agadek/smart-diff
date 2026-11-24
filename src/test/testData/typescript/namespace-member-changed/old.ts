export namespace Geometry {
  export const PI = 3.14159;

  export function circle(radius: number): number {
    return PI * radius * radius;
  }
}

declare module "legacy-lib" {
  export function init(options?: object): void;
}
