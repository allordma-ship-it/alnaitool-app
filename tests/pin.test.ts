import { describe, it, expect } from 'vitest';
import { makePin, checkPin, normalizePin, lockoutSeconds } from '../src/pin';

describe('PIN', () => {
  it('stores only a salted hash and checks it', async () => {
    const rec = await makePin('2580', 1000);
    expect(rec.hash).not.toContain('2580');
    expect(rec.salt).toHaveLength(32);
    expect(await checkPin('2580', rec)).toBe(true);
    expect(await checkPin('2581', rec)).toBe(false);
    const again = await makePin('2580', 1000);
    expect(again.hash).not.toBe(rec.hash); // different salt, different hash
  });

  it('accepts 4–8 digits, including Arabic-Indic ones', () => {
    expect(normalizePin('١٢٣٤')).toBe('1234');
    expect(normalizePin(' 98765 ')).toBe('98765');
    expect(normalizePin('123')).toBeNull();
    expect(normalizePin('12a4')).toBeNull();
    expect(normalizePin('123456789')).toBeNull();
  });

  it('waits longer after repeated wrong PINs', () => {
    expect(lockoutSeconds(4)).toBe(0);
    expect(lockoutSeconds(5)).toBe(30);
    expect(lockoutSeconds(6)).toBe(60);
    expect(lockoutSeconds(20)).toBe(600);
  });
});
