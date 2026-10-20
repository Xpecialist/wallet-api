import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { setToken, setUnauthorizedHandler } from '../src/api/client.js';

afterEach(() => {
  cleanup();
  setToken(null);
  setUnauthorizedHandler(() => {});
  sessionStorage.clear();
});
