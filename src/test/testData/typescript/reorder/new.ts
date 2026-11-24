import { Repository } from "./repository";

/** Loads and stores users. */
export class UserService {
  private cache = new Map<string, User>();

  constructor(private readonly repository: Repository) {}

  save(user: User): void {
    this.cache.set(user.id, user);
    this.repository.store(user);
  }

  async find(id: string): Promise<User | undefined> {
    return this.cache.get(id) ?? this.repository.load(id);
  }
}

export interface User {
  id: string;
  name: string;
}
