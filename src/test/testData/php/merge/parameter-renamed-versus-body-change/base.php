<?php

namespace Svc;

class Service
{
    public function start(): void
    {
    }

    public function a(int $x): int
    {
        return $x;
    }

    public function b(): int
    {
        return 2;
    }
}
