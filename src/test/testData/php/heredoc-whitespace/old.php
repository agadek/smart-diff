<?php

function banner(string $name): string
{
    return <<<EOT
        Hello $name
        welcome
    EOT;
}

function plain(): string
{
    return <<<'EOT'
        a  b
    EOT;
}
