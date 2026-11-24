export interface Shape {
  readonly kind: string;
  area(): number;
}

export type ShapeKind = "circle" | "square" | "triangle";

export enum Unit {
  Millimetre,
  Inch,
}
