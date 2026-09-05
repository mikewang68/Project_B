import type { SystemSettingsGroup, SystemSettingsGroupId } from '../systemSettingsTypes';

type Props = Readonly<{
  groups: readonly SystemSettingsGroup[];
  selected: SystemSettingsGroupId;
  onSelect(group: SystemSettingsGroupId): void;
}>;

export function SystemSettingsGroupNav({ groups, selected, onSelect }: Props) {
  return (
    <nav className="system-settings-groups" aria-label="系统配置分组">
      <p className="system-settings-section-kicker">配置分组</p>
      {groups.map((group) => (
        <button
          className={group.id === selected ? 'is-active' : ''}
          key={group.id}
          type="button"
          aria-label={group.label}
          aria-current={group.id === selected ? 'page' : undefined}
          onClick={() => onSelect(group.id)}
        >
          <strong>{group.label}</strong>
          <span>{group.description}</span>
        </button>
      ))}
    </nav>
  );
}
