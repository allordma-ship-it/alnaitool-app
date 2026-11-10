export type Tool = 'pen' | 'highlighter' | 'eraser' | 'lasso' | 'text';
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

/** A typed text box in page coordinates. Lines wrap at `w`; direction follows the text itself. */
export interface TextBox {
  id: string;
  x: number;
  y: number;
  w: number;
  text: string;
  size: number;
  color: string;
  bold?: boolean;
  /** note bubble: tail points at (ax, ay); h = minimum inner height so there is room to handwrite */
  bubble?: { ax: number; ay: number; h: number };
}

export type PageBackground =
  | { kind: 'template'; template: Template; paper: PaperColor; rtl?: boolean }
  | { kind: 'pdf'; pdfPage: number }
  | { kind: 'image'; fileId: string; mime: string };

export interface PageData {
  id: string;
  w: number;
  h: number;
  bg: PageBackground;
  strokes: Stroke[];
  texts?: TextBox[];
}

export interface DocMeta {
  id: string;
  title: string;
  kind: 'notebook' | 'pdf' | 'images';
  createdAt: number;
  updatedAt: number;
  pageIds: string[];
  thumb?: string;
  /** default template for new pages */
  template: Template;
  paper: PaperColor;
  lastPage?: number;
  /** extra stored files (image pages) to delete with the document */
  fileIds?: string[];
  /** open in reading mode (no drawing) */
  reading?: boolean;
}

export const A4 = { w: 595.28, h: 841.89 };

export function uid(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
}
