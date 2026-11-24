<?php

function label(string $name): string
{
    return prefix($name);
}

function prefix(string $name): string
{
    return 'item: ' . strtoupper($name);
}
