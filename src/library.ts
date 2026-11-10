import { listDocs, putDoc, putPages, deleteDoc, getPages } from './db';
import { A4, uid, type DocMeta, type PageData, type Template, type PaperColor } from './types';
import { importFiles } from './importFiles';
import { convertDoc, shareFiles } from './share';
import { settings } from './settings';
import { drawTemplate } from './ink';
import { openSettings } from './settingsDialog';
import { icons } from './icons';
import { t } from './i18n';
import { h, modal, toast } from './ui';

export class Library {
  root = h('div', { class: 'library' });
  private grid = h('div', { class: 'grid' });
  private query = '';

  constructor(private openDoc: (d: DocMeta) => void) {}

  async mount() {
    const btn = (icon: string, label: string, fn: () => void, cls = '') => {
      const b = h('button', { class: `btn ${cls}` });
      b.innerHTML = icon;
      b.append(h('span', {}, label));
      b.addEventListener('click', fn);
      return b;
    };
    const fileInput = h('input', {
      type: 'file', multiple: true, hidden: true,
      accept: 'application/pdf,image/*,text/plain,text/markdown,.pdf,.docx,.txt,.md,application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });
    fileInput.addEventListener('change', async () => {
      await this.import([...(fileInput.files ?? [])]);
      fileInput.value = '';
    });
    const search = h('input', { type: 'search', class: 'search', placeholder: t('search') });
    search.addEventListener('input', () => { this.query = search.value.trim().toLowerCase(); void this.refresh(); });
    const settingsBtn = h('button', { class: 'icon-btn', title: t('settings'), 'aria-label': t('settings') });
    settingsBtn.innerHTML = icons.settings;
    settingsBtn.addEventListener('click', () => openSettings(() => {}));

    this.root.append(
      h('header', { class: 'lib-head' },
        h('h1', {}, t('appName')),
        search,
        btn(icons.plus, t('newNotebook'), () => this.newNotebookDialog(), 'primary'),
        btn(icons.file, t('importFile'), () => fileInput.click()),
        settingsBtn,
        fileInput,
      ),
      this.grid,
    );
    // drag & drop PDFs (desktop)
    this.root.addEventListener('dragover', (e) => e.preventDefault());
    this.root.addEventListener('drop', async (e) => {
      e.preventDefault();
      await this.import([...(e.dataTransfer?.files ?? [])]);
    });
    await this.refresh();
  }

  async refresh() {
    const docs = (await listDocs()).filter((d) => !this.query || d.title.toLowerCase().includes(this.query));
    this.grid.replaceChildren();
    if (!docs.length) {
      this.grid.append(h('p', { class: 'empty' }, t('empty')));
      return;
    }
    for (const d of docs) {
      const thumb = h('div', { class: 'thumb' });
      if (d.thumb) thumb.append(h('img', { src: d.thumb, alt: '' }));
      else thumb.innerHTML = d.kind === 'pdf' ? icons.file : d.kind === 'images' ? icons.image : icons.pen;
      const more = h('button', { class: 'icon-btn more', 'aria-label': '…' });
      more.innerHTML = icons.more;
      more.addEventListener('click', (e) => { e.stopPropagation(); this.docMenu(d); });
      const card = h('div', { class: 'card', tabindex: 0, role: 'button' },
        thumb,
        h('div', { class: 'card-meta' },
          h('div', { class: 'card-title' }, d.title),
          h('div', { class: 'card-sub' }, `${d.kind === 'pdf' ? 'PDF · ' : ''}${d.pageIds.length} ${t('pages')} · ${new Date(d.updatedAt).toLocaleDateString(document.documentElement.lang)}`),
        ),
        more,
      );
      card.addEventListener('click', () => this.openDoc(d));
      card.addEventListener('keydown', (e) => { if (e.key === 'Enter') this.openDoc(d); });
      this.grid.append(card);
    }
  }

  private docMenu(d: DocMeta) {
    const form = h('form', { method: 'dialog', class: 'menu' },
      h('h2', {}, d.title),
      h('button', { value: 'share' }, t('share')),
      h('button', { value: 'rename' }, t('rename')),
      h('button', { value: 'delete', class: 'danger' }, t('delete')),
      h('button', { value: 'cancel' }, t('cancel')),
    );
    modal(form).closed.then(async (v) => {
      if (v === 'share') {
        toast(t('preparing'));
        try {
          await shareFiles(await convertDoc(d, await getPages(d.pageIds), 'pdf'), d.title);
        } catch (err) {
          console.error(err);
          toast(String(err));
        }
      } else if (v === 'rename') {
        const name = prompt(t('title'), d.title)?.trim();
        if (name) { d.title = name; await putDoc(d); }
      } else if (v === 'delete' && confirm(t('confirmDelete'))) {
        await deleteDoc(d);
      }
      await this.refresh();
    });
  }

  private newNotebookDialog() {
    let template: Template = 'lined';
    let paper: PaperColor = 'white';
    const preview = (tp: Template, pp: PaperColor) => {
      const c = h('canvas', { width: 90, height: 127 });
      const ctx = c.getContext('2d')!;
      ctx.scale(90 / A4.w, 127 / A4.h);
      drawTemplate(ctx, A4.w, A4.h, tp, pp, settings.lang === 'ar');
      return c;
    };
    const tplRow = h('div', { class: 'tpl-row' });
    const renderTpls = () => {
      tplRow.replaceChildren();
      for (const tp of ['blank', 'lined', 'grid', 'dots', 'cornell'] as Template[]) {
        const b = h('button', { type: 'button', class: `tpl ${tp === template ? 'active' : ''}` }, preview(tp, paper), h('span', {}, t(tp)));
        b.addEventListener('click', () => { template = tp; renderTpls(); });
        tplRow.append(b);
      }
    };
    const paperRow = h('div', { class: 'seg' });
    const renderPaper = () => {
      paperRow.replaceChildren();
      for (const pp of ['white', 'cream', 'dark'] as PaperColor[]) {
        const b = h('button', { type: 'button', class: pp === paper ? 'active' : '' }, t(pp));
        b.addEventListener('click', () => { paper = pp; renderPaper(); renderTpls(); });
        paperRow.append(b);
      }
    };
    renderTpls();
    renderPaper();
    const title = h('input', { type: 'text', value: t('untitled'), class: 'text' });
    const form = h('form', { method: 'dialog', class: 'new-nb' },
      h('h2', {}, t('newNotebook')),
      h('label', { class: 'row col' }, h('span', {}, t('title')), title),
      h('div', { class: 'row col' }, h('span', {}, t('template')), tplRow),
      h('div', { class: 'row col' }, h('span', {}, t('paper')), paperRow),
      h('div', { class: 'actions' },
        h('button', { value: 'cancel', type: 'submit' }, t('cancel')),
        h('button', { value: 'create', type: 'submit', class: 'primary' }, t('create')),
      ),
    );
    modal(form).closed.then(async (v) => {
      if (v !== 'create') return;
      const page: PageData = { id: uid(), w: A4.w, h: A4.h, bg: { kind: 'template', template, paper, rtl: settings.lang === 'ar' }, strokes: [] };
      const now = Date.now();
      const doc: DocMeta = {
        id: uid(), title: title.value.trim() || t('untitled'), kind: 'notebook',
        createdAt: now, updatedAt: now, pageIds: [page.id], template, paper,
      };
      await putPages([page]);
      await putDoc(doc);
      this.openDoc(doc);
    });
  }

  async import(files: File[]) {
    if (!files.length) return;
    toast(t('loading'));
    const { docs, failed, unsupported } = await importFiles(files);
    await this.refresh();
    if (unsupported.length) toast(`${t('unsupported')}`);
    else if (failed.length) toast(`${t('fileError')}: ${failed.join(', ')}`);
    else if (docs.length === 1) this.openDoc(docs[0]);
  }
}
