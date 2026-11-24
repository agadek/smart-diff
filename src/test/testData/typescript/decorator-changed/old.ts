@Component({ selector: "app-list" })
export class ListComponent {
  @Input() items: string[] = [];

  @Output() picked = new EventEmitter<string>();

  pick(item: string): void {
    this.picked.emit(item);
  }
}
