import type { InkTool } from './types';

export type ButtonAction = 'eraser' | 'highlighter' | 'lasso' | 'laser' | 'clip' | 'shape' | 'none';
export type Lang = 'ar' | 'de' | 'en';

export interface Settings {
  lang: Lang;
  /** auto: become pen-only as soon as a stylus is seen */
  penOnly: 'auto' | 'on' | 'off';
  barrelButton: ButtonAction;
  secondButton: ButtonAction;
  twoFingerUndo: boolean;
  pressure: boolean;
  eraserMode: 'stroke' | 'precise';
  eraserSize: number;
  colors: Record<InkTool | 'shape', string>;
  widths: Record<InkTool | 'shape', number>;
  palette: string[];
  textColor: string;
  textSize: number;
  /** language used for handwriting recognition */
  hwLang: Lang | null;
  /** straighten highlights and underlines automatically */
  straighten: boolean;
  /** holding the pen still opens a note bubble */
  holdBubble: boolean;
  /** pressure curve exponent: <1 softer (less force for thick lines), >1 firmer */
  pressureCurve: number;
  /** highlighter gets wider when the pen is tilted, like a chisel marker */
  tiltHighlighter: boolean;
  /** eye-care: invert PDF and picture pages to dark */
  eyeCare: boolean;
  /** page layout: one column, or two pages side by side */
  spread: 'single' | 'dual';
  theme: 'dark' | 'light';
  /** live pressure/tilt indicator in the header */
  showHud: boolean;
  notesPanel: boolean;
}

const DEFAULTS: Settings = {
  lang: 'ar',
  penOnly: 'auto',
  barrelButton: 'eraser',
  secondButton: 'lasso',
  twoFingerUndo: true,
  pressure: true,
  eraserMode: 'stroke',
  eraserSize: 12,
  colors: { pen: '#0f172a', highlighter: '#ffe14d', shape: '#06b6d4' },
  widths: { pen: 2, highlighter: 14, shape: 2 },
  textColor: '#1a1a2e',
  textSize: 16,
  hwLang: null,
  straighten: true,
  holdBubble: true,
  pressureCurve: 1,
  tiltHighlighter: true,
  eyeCare: false,
  spread: 'single',
  theme: 'dark',
  showHud: true,
  notesPanel: false,
  palette: ['#0f172a', '#06b6d4', '#2563eb', '#ef4444', '#10b981', '#8b5cf6', '#f59e0b', '#ffe14d'],
};

const KEY = 'alnai-settings';

function load(): Settings {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const s = JSON.parse(raw);
      return {
        ...DEFAULTS, ...s,
        colors: { ...DEFAULTS.colors, ...s.colors },
        widths: { ...DEFAULTS.widths, ...s.widths },
      };
    }
  } catch {
    /* storage unavailable */
  }
  return structuredClone(DEFAULTS);
}

export const settings: Settings = load();

export function saveSettings() {
  try {
    localStorage.setItem(KEY, JSON.stringify(settings));
  } catch {
    /* ignore */
  }
}
