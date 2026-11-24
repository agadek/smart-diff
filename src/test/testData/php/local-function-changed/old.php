<?php

function outer(): callable
{
    $f = function (int $x) { return $x + 1; };
    return $f;
}
