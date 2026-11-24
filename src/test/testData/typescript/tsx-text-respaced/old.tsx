import { useState } from "react";

type Props = { label: string; initial?: number };

export function Counter({ label, initial = 0 }: Props) {
  const [count, setCount] = useState(initial);
  return (
    <button className="counter" onClick={() => setCount(count + 1)}>
      {label}: {count}
    </button>
  );
}
