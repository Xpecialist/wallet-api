import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useFetch } from '../src/utils/useFetch.js';

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => ((resolve = a), (reject = b)));
  return { promise, resolve, reject };
};

describe('useFetch', () => {
  it('starts loading, then exposes data', async () => {
    const d = deferred();
    const { result } = renderHook(() => useFetch(() => d.promise, []));
    expect(result.current.loading).toBe(true);
    expect(result.current.data).toBeNull();
    await act(async () => d.resolve({ a: 1 }));
    expect(result.current).toMatchObject({ loading: false, data: { a: 1 }, error: '' });
  });

  it('exposes the error message and null data on failure', async () => {
    const { result } = renderHook(() => useFetch(() => Promise.reject(new Error('boom')), []));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current).toMatchObject({ error: 'boom', data: null });
  });

  it('reload re-runs the loader', async () => {
    const loader = vi.fn().mockResolvedValueOnce('one').mockResolvedValueOnce('two');
    const { result } = renderHook(() => useFetch(loader, []));
    await waitFor(() => expect(result.current.data).toBe('one'));
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.data).toBe('two'));
    expect(loader).toHaveBeenCalledTimes(2);
  });

  it('re-runs when deps change', async () => {
    const loader = vi.fn(async () => 'x');
    const { result, rerender } = renderHook(({ n }) => useFetch(loader, [n]), { initialProps: { n: 1 } });
    await waitFor(() => expect(result.current.data).toBe('x'));
    rerender({ n: 2 });
    await waitFor(() => expect(loader).toHaveBeenCalledTimes(2));
  });

  it('clears a previous error when reloading successfully', async () => {
    const loader = vi.fn().mockRejectedValueOnce(new Error('bad')).mockResolvedValueOnce('ok');
    const { result } = renderHook(() => useFetch(loader, []));
    await waitFor(() => expect(result.current.error).toBe('bad'));
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.data).toBe('ok'));
    expect(result.current.error).toBe('');
  });

  it('ignores a late result after unmount (no warning)', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    const d = deferred();
    const { unmount } = renderHook(() => useFetch(() => d.promise, []));
    unmount();
    await act(async () => d.resolve('late'));
    expect(err).not.toHaveBeenCalled();
  });

  it('ignores a stale response when deps changed meanwhile', async () => {
    const first = deferred();
    const second = deferred();
    const loader = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result, rerender } = renderHook(({ n }) => useFetch(loader, [n]), { initialProps: { n: 1 } });
    rerender({ n: 2 });
    await act(async () => second.resolve('new'));
    await act(async () => first.resolve('old'));
    expect(result.current.data).toBe('new');
  });
});
