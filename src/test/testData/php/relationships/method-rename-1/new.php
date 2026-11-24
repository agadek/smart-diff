<?php

class Stats
{
    public function sum(array $values): int
    {
        $total = 0;
        foreach ($values as $v) {
            $total += $v;
        }
        return $total;
    }

    public function second(array $values): int
    {
        $count = 0;
        foreach ($values as $v) {
            $count++;
        }
        return $count;
    }
}
