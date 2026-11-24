export class Reader {
  read(path: string): Buffer {
    return load(path);
  }
}

export class Writer {
  write(path: string, data: Buffer): void {
    store(path, data);
  }

  close(): void {
    release();
  }
}
