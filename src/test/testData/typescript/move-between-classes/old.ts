export class Reader {
  read(path: string): Buffer {
    return load(path);
  }

  close(): void {
    release();
  }
}

export class Writer {
  write(path: string, data: Buffer): void {
    store(path, data);
  }
}
