import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Alert } from '../src/components/Feedback.jsx';

describe('Alert styling', () => {
  it('shows a rate-limit (429) message with the warning style but the same alert role', () => {
    render(<Alert>Too many attempts. Try again in 12 seconds.</Alert>);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Too many attempts. Try again in 12 seconds.');
    expect(alert).toHaveClass('alert-warning');
    expect(alert).not.toHaveClass('alert-error');
  });

  it('keeps ordinary errors red', () => {
    render(<Alert>Insufficient funds</Alert>);
    expect(screen.getByRole('alert')).toHaveClass('alert-error');
  });
});
