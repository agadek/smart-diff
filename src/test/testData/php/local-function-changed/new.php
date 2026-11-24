<?php

function outer(): callable
{
    $f = function (int $x) { return $x + 2; };
    return $f;
}
