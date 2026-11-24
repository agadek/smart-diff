class Reader {
  read(path) {
    return load(path);
  }
}

class Writer {
  write(path, data) {
    store(path, data);
  }

  close() {
    release();
  }
}
