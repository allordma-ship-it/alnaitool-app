import type { InkTool } from './types';

export type ButtonAction = 'eraser' | 'highlighter' | 'lasso' | 'none';
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
  colors: Record<InkTool, string>;
  widths: Record<InkTool, number>;
  palette: string[];
  textColor: string;
  textSize: number;
  /** language used for handwriting recognition */
  hwLang: Lang | null;
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
  colors: { pen: '#1a1a2e', highlighter: '#ffe14d' },
  widths: { pen: 2, highlighter: 14 },
  textColor: '#1a1a2e',
  textSize: 16,
  hwLang: null,
  palette: ['#1a1a2e', '#2156d1', '#d62839', '#1f8a4c', '#8e44ad', '#f39c12', '#ffe14d', '#7fe0ff'],
};

const KEY = 'alnai-settings';

function load(): Settings {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return { ...DEFAULTS, ...JSON.parse(raw) };
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
