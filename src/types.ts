export type Tool = 'pen' | 'highlighter' | 'eraser' | 'lasso';
export type InkTool = 'pen' | 'highlighter';
export type Template = 'blank' | 'lined' | 'grid' | 'dots' | 'cornell';
export type PaperColor = 'white' | 'cream' | 'dark';

/** A stroke in page coordinates (PDF points). pts = [x0,y0,p0, x1,y1,p1, ...] */
export interface Stroke {
  id: string;
  tool: InkTool;
  color: string;
  width: number;
  pts: number[];
  bbox: [number, number, number, number];
}

export type PageBackground =
  | { kind: 'template'; template: Template; paper: PaperColor }
  | { kind: 'pdf'; pdfPage: number };

export interface PageData {
  id: string;
  w: number;
  h: number;
  bg: PageBackground;
  strokes: Stroke[];
}

export interface DocMeta {
  id: string;
  title: string;
  kind: 'notebook' | 'pdf';
  createdAt: number;
  updatedAt: number;
  pageIds: string[];
  thumb?: string;
  /** default template for new pages */
  template: Template;
  paper: PaperColor;
  lastPage?: number;
}

export const A4 = { w: 595.28, h: 841.89 };

export function uid(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
}
