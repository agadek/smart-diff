export const sum = (values: readonly number[]): number => values.reduce((a, b) => a + b, 0);
export const max = (values: number[]): number => Math.max(...values);
