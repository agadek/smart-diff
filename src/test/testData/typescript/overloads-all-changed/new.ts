export function parse(value: string, radix: number): number;
export function parse(value: number, radix: number): number;
export function parse(value: string | number, radix: number): number {
  return typeof value === "number" ? value : parseInt(value, radix);
}
