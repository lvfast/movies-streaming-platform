import { afterEach, describe, expect, it, vi } from 'vitest';
import { accessTokenStore } from './token-store';

describe('accessTokenStore', () => {
  afterEach(() => accessTokenStore.clear());

  it('keeps the bearer token in module memory only', () => {
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem');

    accessTokenStore.set('short-lived-token');

    expect(accessTokenStore.get()).toBe('short-lived-token');
    expect(localStorageSpy).not.toHaveBeenCalled();
  });

  it('clears the token at logout', () => {
    accessTokenStore.set('short-lived-token');
    accessTokenStore.clear();

    expect(accessTokenStore.get()).toBeNull();
  });
});
