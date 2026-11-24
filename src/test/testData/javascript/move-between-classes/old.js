class Reader {
  read(path) {
    return load(path);
  }

  close() {
    release();
  }
}

class Writer {
  write(path, data) {
    store(path, data);
  }
}
