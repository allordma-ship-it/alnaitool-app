export type Tool = 'pen' | 'highlighter' | 'shape' | 'laser' | 'eraser' | 'lasso' | 'clip' | 'text';
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

/** A picture placed on a page (under the ink, so you can write on it). Page coordinates, like strokes. */
export interface PageImage {
  id: string;
  fileId: string;
  mime: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface PageData {
  id: string;
  w: number;
  h: number;
  bg: PageBackground;
  strokes: Stroke[];
  texts?: TextBox[];
  images?: PageImage[];
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
  /** folder in the library; none = top level */
  folderId?: string;
  /** bookmarked pages, by page id (survives reordering) */
  bookmarks?: string[];
  /** pinned documents stay at the top of the library */
  pinned?: boolean;
  /** colour label */
  color?: DocColor;
  /** free tags, e.g. "Asthma", "Exam" */
  tags?: string[];
  /** needs fingerprint or PIN to open */
  locked?: boolean;
}

export const DOC_COLORS = ['red', 'orange', 'yellow', 'green', 'blue', 'purple'] as const;
export type DocColor = (typeof DOC_COLORS)[number];

export interface Folder {
  id: string;
  name: string;
  createdAt: number;
  /** every document inside needs fingerprint or PIN */
  locked?: boolean;
}

/** Searchable text of a PDF document, extracted once and kept so library search is instant. */
export interface TextIndex {
  docId: string;
  /** text of each PDF page, by PDF page number - 1 */
  byPdfPage: string[];
  /** page index -> PDF page number (0 = not a PDF page), valid while `key` matches the doc's pageIds */
  pageMap: number[];
  key: string;
}

/**
 * An entry in the QalamNote study notebook: a clipped region of a page (picture + any text found
 * there) or a voice memo, always linked back to its source page.
 */
export interface Clip {
  id: string;
  kind: 'clip' | 'audio';
  docId: string;
  docTitle: string;
  /** index into the document's pages */
  page: number;
  /** id of that page, so the index can be corrected when pages are moved or deleted */
  pageId?: string;
  /** region in page coordinates [x0, y0, x1, y1] */
  rect?: [number, number, number, number];
  /** JPEG data URL of the clipped region */
  image?: string;
  /** text extracted from the PDF under the region */
  text?: string;
  /** the user's own note / flashcard question */
  note: string;
  audioId?: string;
  audioMime?: string;
  duration?: number;
  createdAt: number;
}

export const A4 = { w: 595.28, h: 841.89 };

export function uid(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
}
