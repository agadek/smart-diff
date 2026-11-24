<?php

function second(array $values): int
{
    $count = 0;
    foreach ($values as $v) {
        $count++;
    }
    return $count;
}

function first(array $values): int
{
    $total = 0;
    foreach ($values as $v) {
        $total += $v;
    }
    return $total;
}
