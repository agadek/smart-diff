export const GREETING = "Hello";

export function greet(name: string): string {
  return `${GREETING}, dear ${name}!`;
}
