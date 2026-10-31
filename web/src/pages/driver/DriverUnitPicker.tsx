import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import type { DriverUnit } from '@/features/driver/api';

/** The driver's units; a single unit needs no choice but still shows which one it is. */
export function DriverUnitPicker({ units, value, onChange }: { units: DriverUnit[]; value: string; onChange: (id: string) => void }) {
  return (
    <Field label={tx('Vehicle / equipment *')}>
      <OptionalSelect value={value} onChange={onChange} placeholder={tx('Choose a vehicle')}
        options={units.map((u) => ({ value: u.id, label: [u.code, u.registrationPlate].filter(Boolean).join(' · ') }))} />
    </Field>
  );
}
