<?php

function total(array $items): int
{
    $sum = 0;
    foreach ($items as $i) {
        $sum += $i * 2;
    }
    return $sum + 1;
}

function extra(array $items): int
{
    $sum = 0;
    foreach ($items as $i) {
        $sum += $i * 2;
    }
    return $sum - 1;
}
