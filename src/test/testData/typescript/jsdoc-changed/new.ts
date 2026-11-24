import { Repository } from "./repository";

/** Loads users and stores them in the repository. */
export class UserService {
  private cache = new Map<string, User>();

  constructor(private readonly repository: Repository) {}

  async find(id: string): Promise<User | undefined> {
    return this.cache.get(id) ?? this.repository.load(id);
  }

  save(user: User): void {
    this.cache.set(user.id, user);
    this.repository.store(user);
  }
}

export interface User {
  id: string;
  name: string;
}
