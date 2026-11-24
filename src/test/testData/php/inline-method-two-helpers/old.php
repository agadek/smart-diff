<?php

function run(array $items): int
{
    $a = first($items);
    $b = second($items);
    return $a + $b;
}

function first(array $items): int
{
    $sum = 0;
    foreach ($items as $i) {
        $sum += $i;
    }
    return $sum;
}

function second(array $items): int
{
    $max = 0;
    foreach ($items as $i) {
        $max = max($max, $i);
    }
    return $max;
}
