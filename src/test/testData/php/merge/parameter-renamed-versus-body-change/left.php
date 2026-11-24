<?php

namespace Svc;

class Service
{
    public function start(): void
    {
    }

    public function a(int $value): int
    {
        return $value;
    }

    public function b(): int
    {
        return 2;
    }
}
