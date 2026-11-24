export interface Shape {
  readonly kind: string;
  area(): number;
}

export type ShapeKind = "circle" | "square";

export enum Unit {
  Millimetre,
  Inch,
  Point,
}
