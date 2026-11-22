import { describe, expect, it } from 'vitest';
import { rawTopLevelValue, scopeChecksum, sha256Hex, verifyPage } from '../checksum';

describe('snapshot checksums', () => {
  it('extracts the raw top-level value, ignoring nested keys and strings that look like JSON', () => {
    const raw = '{"meta":{"rows":[9]},"note":"\\"rows\\":[1]","rows" : [{"id":"a","price":1.50,"x":"]"}],"z":1}';
    expect(rawTopLevelValue(raw, 'rows')).toBe('[{"id":"a","price":1.50,"x":"]"}]');
    expect(rawTopLevelValue(raw, 'z')).toBe('1');
    expect(rawTopLevelValue(raw, 'missing')).toBeNull();
  });

  it('verifies against the raw bytes (1.50 is not re-serialized to 1.5)', async () => {
    const rows = '[{"id":"a","amount":1.50,"10":"x","2":"y"}]';
    const sum = await sha256Hex(rows);
    expect(await verifyPage(`{"rows":${rows},"pageChecksum":"${sum}"}`, sum)).toBe(true);
    expect(await sha256Hex(JSON.stringify(JSON.parse(rows)))).not.toBe(sum);
    expect(await verifyPage(`{"rows":${rows.replace('1.50', '1.51')}}`, sum)).toBe(false);
  });

  it('scope checksum = sha256 of page checksums joined by commas', async () => {
    expect(await scopeChecksum(['a', 'b'])).toBe(await sha256Hex('a,b'));
  });
});
