<?php

namespace Svc;

class Service
{
    public function start(): void
    {
    }

    #[\Deprecated]
    public function a(): int
    {
        return 1;
    }

    public function b(): int
    {
        return 2;
    }
}
