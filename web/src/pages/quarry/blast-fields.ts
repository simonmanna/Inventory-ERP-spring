import { tx } from '@/lib/i18n/tx';
import type { QuarryLookups } from '@/features/quarry/api';
import type { FieldSpec } from './quarry-ui';

/** Form of a drilling & blasting record: pattern, drilling, costs, compliance. */
export function blastFields(L?: QuarryLookups, shotfirerLabel?: string | null): FieldSpec[] {
  return [
    { name: 'siteId', label: tx('Site'), type: 'select', required: true, options: (L?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` })) },
    { name: 'zoneId', label: tx('Bench / face'), type: 'select', options: (L?.zones ?? []).map((z) => ({ value: z.id, label: `${z.code} · ${z.name}` })) },
    { name: 'materialId', label: tx('Material'), type: 'select', options: (L?.materials ?? []).map((m) => ({ value: m.id, label: m.name })), hint: tx('Its density turns the pattern volume into tonnes') },
    { name: 'plannedAt', label: tx('Planned for'), type: 'datetime' },
    { name: 'patternType', label: tx('Pattern'), type: 'select', options: [{ value: 'square', label: tx('Square') }, { value: 'staggered', label: tx('Staggered') }, { value: 'other', label: tx('Other') }] },
    { name: 'holeCount', label: tx('Holes'), type: 'number', min: 1 },
    { name: 'holeDiameterMm', label: tx('Hole diameter (mm)'), type: 'number', min: 0 },
    { name: 'averageDepthM', label: tx('Average depth (m)'), type: 'number', min: 0 },
    { name: 'burdenM', label: tx('Burden (m)'), type: 'number', min: 0 },
    { name: 'spacingM', label: tx('Spacing (m)'), type: 'number', min: 0 },
    { name: 'subDrillM', label: tx('Sub-drill (m)'), type: 'number', min: 0 },
    { name: 'stemmingM', label: tx('Stemming (m)'), type: 'number', min: 0 },
    { name: 'estimatedTonnes', label: tx('Estimated tonnes'), type: 'number', min: 0, hint: tx('Blank = holes × burden × spacing × bench height × density') },
    { name: 'drillVehicleId', label: tx('Drill rig'), type: 'select', options: (L?.plant ?? []).map((v) => ({ value: v.id, label: `${v.code} · ${v.className}` })) },
    { name: 'drillHours', label: tx('Drill hours'), type: 'number', min: 0 },
    { name: 'drillingCost', label: tx('Drilling cost'), type: 'number', min: 0 },
    { name: 'otherCost', label: tx('Other blast cost'), type: 'number', min: 0 },
    { name: 'shotfirerEmployeeId', label: tx('Shotfirer'), type: 'employee', pickedLabel: shotfirerLabel },
    { name: 'shotfirerLicenceNo', label: tx('Shotfirer licence number') },
    { name: 'blastPermitNo', label: tx('Blast permit number') },
    { name: 'permitExpiresOn', label: tx('Permit expires on'), type: 'date' },
    { name: 'exclusionZoneM', label: tx('Exclusion zone (m)'), type: 'number', min: 0 },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
}

export const fireFields = (): FieldSpec[] => [
  { name: 'firedAt', label: tx('Fired at'), type: 'datetime', required: true },
  { name: 'warningSignalAt', label: tx('Warning signal at'), type: 'datetime' },
  { name: 'allClearAt', label: tx('All-clear at'), type: 'datetime' },
  { name: 'misfireCount', label: tx('Misfires'), type: 'number', min: 0 },
  { name: 'misfireNotes', label: tx('Misfire notes'), wide: true },
  { name: 'vibrationPpvMmS', label: tx('Ground vibration PPV (mm/s)'), type: 'number', min: 0 },
  { name: 'airblastDb', label: tx('Airblast (dB)'), type: 'number', min: 0 },
  { name: 'weather', label: tx('Weather') },
  { name: 'notes', label: tx('Notes'), type: 'textarea' },
];
