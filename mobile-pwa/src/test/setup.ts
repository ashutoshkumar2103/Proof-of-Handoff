import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';

// jsdom does not implement scrolling; the shell scrolls to the top on every screen change.
beforeEach(() => { if (typeof window !== 'undefined') window.scrollTo = () => {}; });
// Component tests render into jsdom; each starts from an empty document.
afterEach(() => cleanup());
