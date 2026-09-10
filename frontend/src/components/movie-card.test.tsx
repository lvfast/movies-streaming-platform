import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import '../styles.css';
import { featuredMovie } from '../test/fixtures';
import { MovieRail } from './movie-card';

describe('MovieRail', () => {
  it('keeps snap positions aligned with the horizontal rail padding', () => {
    render(
      <MemoryRouter>
        <MovieRail title="New releases" movies={[featuredMovie]} />
      </MemoryRouter>,
    );

    const track = screen.getByRole('heading', { name: 'New releases' }).nextElementSibling;
    expect(track).not.toBeNull();

    const style = window.getComputedStyle(track as Element);
    expect(style.scrollPaddingInline).not.toBe('');
    expect(style.scrollPaddingInline).toBe(style.paddingInline);
  });
});
