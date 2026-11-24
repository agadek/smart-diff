<?php

function run(array $items): int
{
    $sum = 0;
    foreach ($items as $i) {
        $sum += $i;
    }
    $max = 0;
    foreach ($items as $i) {
        $max = max($max, $i);
    }
    return $sum + $max;
}
