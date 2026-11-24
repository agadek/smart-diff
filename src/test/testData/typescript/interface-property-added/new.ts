export interface Shape {
  readonly kind: string;
  area(): number;
  label?: string;
}

export type ShapeKind = "circle" | "square";

export enum Unit {
  Millimetre,
  Inch,
}
