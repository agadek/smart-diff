<?php

namespace App\Http {
    function handle(): int { return 1; }
}

namespace App\Cli {
    function handle(): int { return 3; }
}

namespace {
    function boot(): void { echo 1; }
}
