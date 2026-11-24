<?php

namespace Svc;

class Service
{
    public function start(): void
    {
    }

    public function a(int $x): int
    {
        return $x + 1;
    }

    public function b(): int
    {
        return 2;
    }
}
