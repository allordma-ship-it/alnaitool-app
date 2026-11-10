const s = (d: string) =>
  `<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;

export const icons = {
  pen: s('<path d="M4 20l4-1 11-11a2.1 2.1 0 0 0-3-3L5 16l-1 4z"/><path d="M14 6l3 3"/>'),
  highlighter: s('<path d="M9 15l-4 4h5l2-2"/><path d="M9 15l7-11 4 3-7 11z"/><path d="M4 21h16"/>'),
  eraser: s('<path d="M16 3l5 5-10 10H6l-3-3z"/><path d="M9 21h12"/><path d="M11 8l5 5"/>'),
  lasso: s('<path d="M7 18c-3-1-4-4-3-7 2-5 10-7 14-4 3 2 2 6-2 8-3 1-6 1-8 0"/><circle cx="7" cy="19" r="1.5"/><path d="M7 20.5c0 1.5-1 2.5-2 2.5"/>'),
  undo: s('<path d="M9 14L4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>'),
  redo: s('<path d="M15 14l5-5-5-5"/><path d="M20 9H9a5 5 0 0 0 0 10h3"/>'),
  back: s('<path d="M15 18l-6-6 6-6"/>'),
  plus: s('<path d="M12 5v14M5 12h14"/>'),
  trash: s('<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>'),
  share: s('<path d="M12 3v12M7 8l5-5 5 5"/><path d="M5 14v6h14v-6"/>'),
  settings: s('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'),
  file: s('<path d="M14 3H6v18h12V7z"/><path d="M14 3v4h4"/>'),
  copy: s('<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>'),
  fit: s('<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>'),
  more: s('<circle cx="5" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="19" cy="12" r="1.2"/>'),
  book: s('<path d="M12 6c-2-1.5-5-2-8-1.5v13c3-.5 6 0 8 1.5 2-1.5 5-2 8-1.5v-13c-3-.5-6 0-8 1.5z"/><path d="M12 6v13"/>'),
  text: s('<path d="M5 6V4h14v2M12 4v16M9 20h6"/>'),
  toText: s('<path d="M3 15c2-4 3-4 4-1s2 3 4-1"/><path d="M13 12h3M15 10l2 2-2 2"/><path d="M18 8V7h4v1M20 7v10M19 17h2"/>'),
  image: s('<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="M4 18l5-5 4 4 3-3 4 4"/>'),
  pages: s('<rect x="4" y="3" width="12" height="15" rx="1.5"/><path d="M8 21h10a2 2 0 0 0 2-2V7"/>'),
};
