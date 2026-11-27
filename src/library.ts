import { listDocs, putDoc, putPages, deleteDoc, getPages, listClips, listFolders, putFolder, deleteFolder } from './db';
import { searchContent, indexDoc, unindexed, type Hit } from './search';
import { createBackup, lastBackup } from './backup';
import { reminderDialog, cancelDocReminders } from './reminders';
import { activeReminders } from './reminderTime';
import { templateFromFile, templateRef, pageFromTemplate } from './templates';
import { imageUrl } from './pageImages';
import { listTemplates, deleteTemplate } from './db';
import type { CustomTemplate } from './types';
import { setLockedFolders, isLocked, isHidden, folderHidden, ensureFolderUnlocked, ensureUnlocked, toggleDocLock, toggleFolderLock, lockEnabled, reconcileAll } from './lock';
import { A4, uid, DOC_COLORS, type DocMeta, type PageData, type Template, type PaperColor, type Folder, type DocColor } from './types';
import { sortDocs, allTags, cleanTags, SORT_MODES, type SortMode } from './libraryOrder';
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
  private hero = h('section', { class: 'hero' });
  private stats = h('div', { class: 'stats' });
  private query = '';
  private folderBar = h('nav', { class: 'folders', 'aria-label': t('folders') });
  private results = h('section', { class: 'results' });
  private folderId: string | null = readFolder();
  private tagFilter: string | null = readPref(TAG_KEY) || null;
  private sortMode: SortMode = (SORT_MODES as string[]).includes(readPref(SORT_KEY)) ? (readPref(SORT_KEY) as SortMode) : 'edited';
  private tools = h('div', { class: 'lib-tools' });
  private folders: Folder[] = [];
  private searchTimer = 0;
  private searchSeq = 0;

  constructor(private openDoc: (d: DocMeta) => void) {}

  async mount() {
    const btn = (icon: string, label: string, fn: () => void, cls = '') => {
      // named even when a phone shows only the icon
      const b = h('button', { class: `btn ${cls}`, 'aria-label': label, title: label });
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
    search.addEventListener('input', () => {
      this.query = search.value.trim().toLowerCase();
      void this.refresh();
      clearTimeout(this.searchTimer);
      this.searchTimer = window.setTimeout(() => void this.runContentSearch(), 350);
    });
    const settingsBtn = h('button', { class: 'icon-btn', title: t('settings'), 'aria-label': t('settings') });
    settingsBtn.innerHTML = icons.settings;
    settingsBtn.addEventListener('click', () => openSettings(() => {}));

    const mark = h('div', { class: 'brand-mark big', 'aria-hidden': 'true' });
    mark.innerHTML = icons.logo;
    this.root.append(
      h('header', { class: 'lib-head' },
        h('div', { class: 'brand' }, mark, h('h1', {}, t('appName'))),
        h('div', { class: 'lib-actions' },
          search,
          btn(icons.plus, t('newNotebook'), () => void this.newNotebookDialog(), 'primary'),
          btn(icons.file, t('importFile'), () => fileInput.click()),
          btn(icons.scan, t('scanTitle'), () => void import('./scan').then(({ ScanView }) => new ScanView((d) => this.openDoc(d)).open())),
          settingsBtn,
        ),
        fileInput,
      ),
      this.stats,
      this.hero,
      this.folderBar,
      this.tools,
      this.grid,
      this.results,
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
    const all = await listDocs();
    const clips = await listClips();
    this.folders = await listFolders();
    setLockedFolders(this.folders);
    if (this.folderId && !this.folders.some((f) => f.id === this.folderId)) this.setFolder(null);
    // a search looks through every folder; otherwise show the open folder only
    // a search looks through every folder (titles and tags); otherwise the open folder and tag
    const tags = allTags(all, document.documentElement.lang);
    if (this.tagFilter && !tags.includes(this.tagFilter)) this.setTag(null);
    const docs = sortDocs(all.filter((d) => this.query
      ? d.title.toLowerCase().includes(this.query) || (d.tags ?? []).some((tg) => tg.toLowerCase().includes(this.query))
      : (!this.folderId || d.folderId === this.folderId) && (!this.tagFilter || (d.tags ?? []).includes(this.tagFilter))),
    this.sortMode, document.documentElement.lang);
    this.renderTools(tags);
    if (!this.query) this.results.replaceChildren();
    this.renderFolders(all);
    this.grid.replaceChildren();
    const last = lastBackup();
    const old = !last || Date.now() - last > 7 * 864e5;
    const backupBtn = h('button', { class: `stat backup-stat ${old && all.length ? 'warn' : ''}`, type: 'button' },
      last ? `${t('lastBackup')}: ${new Date(last).toLocaleDateString(document.documentElement.lang)}` : t('neverBackedUp'));
    backupBtn.addEventListener('click', async () => {
      try { await createBackup(); } catch (e) { console.error(e); toast(String(e)); }
      void this.refresh();
    });
    this.stats.replaceChildren(
      h('span', { class: 'stat' }, h('b', {}, String(all.length)), ` ${t('documents')}`),
      h('span', { class: 'stat' }, h('b', {}, String(clips.length)), ` ${t('clipsCount')}`),
      backupBtn,
    );
    this.hero.replaceChildren();
    this.hero.hidden = !!this.query || !all.length || !!this.folderId;
    if (!this.query && !this.folderId && all[0]) {
      const d = all[0];
      const img = h('div', { class: 'hero-thumb' });
      if (isHidden(d)) img.innerHTML = icons.lock;
      else if (d.thumb) img.append(h('img', { src: d.thumb, alt: '' }));
      const open = h('button', { class: 'btn primary' }, t('continueReading'));
      this.hero.append(img, h('div', { class: 'hero-body' },
        h('span', { class: `badge ${d.kind}` }, t(d.kind === 'pdf' ? 'kindPdf' : d.kind === 'images' ? 'kindImages' : 'kindNotebook')),
        h('h2', {}, d.title),
        h('p', {}, `${t('page')} ${(d.lastPage ?? 0) + 1} / ${d.pageIds.length} · ${new Date(d.updatedAt).toLocaleDateString(document.documentElement.lang)}`),
        open,
      ));
      this.hero.onclick = () => this.openDoc(d);
    }
    if (!docs.length && this.query) return;
    if (!docs.length) {
      const empty = h('div', { class: 'empty' });
      empty.innerHTML = icons.notebook;
      empty.append(h('p', {}, t('empty')));
      this.grid.append(empty);
      return;
    }
    for (const d of docs) {
      const thumb = h('div', { class: `thumb ${isHidden(d) ? 'locked' : ''}` });
      // a locked document shows no preview until it is opened with fingerprint or PIN
      if (isHidden(d)) thumb.innerHTML = icons.lock;
      else if (d.thumb) thumb.append(h('img', { src: d.thumb, alt: '' }));
      else thumb.innerHTML = d.kind === 'pdf' ? icons.file : d.kind === 'images' ? icons.image : icons.pen;
      const more = h('button', { class: 'icon-btn more', 'aria-label': '…' });
      more.innerHTML = icons.more;
      more.addEventListener('click', (e) => { e.stopPropagation(); this.docMenu(d); });
      const pin = h('span', { class: 'card-pin', title: t('pinned'), 'aria-label': t('pinned') });
      pin.innerHTML = icons.pin;
      const card = h('div', { class: `card ${d.color ? `c-${d.color}` : ''}`, tabindex: 0, role: 'button' },
        thumb,
        h('span', { class: `badge ${d.kind}` }, t(d.kind === 'pdf' ? 'kindPdf' : d.kind === 'images' ? 'kindImages' : 'kindNotebook')),
        d.pinned ? pin : '',
        isLocked(d) ? h('span', { class: 'card-lock', title: t('locked'), 'aria-label': t('locked') }) : '',
        activeReminders(d.reminders).length ? h('span', { class: 'card-bell', title: t('reminders'), 'aria-label': t('reminders') }) : '',
        h('div', { class: 'card-meta' },
          h('div', { class: 'card-title' }, d.color ? h('i', { class: `cdot c-${d.color}`, 'aria-hidden': 'true' }) : '', d.title),
          h('div', { class: 'card-sub' }, `${d.pageIds.length} ${t('pages')} · ${new Date(d.updatedAt).toLocaleDateString(document.documentElement.lang)}`),
          d.tags?.length ? h('div', { class: 'card-tags' }, ...d.tags.map((tg) => h('span', { dir: 'auto' }, `#${tg}`))) : '',
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
      h('button', { value: 'pin' }, d.pinned ? t('unpin') : t('pin')),
      h('button', { value: 'color' }, t('colorLabel')),
      h('button', { value: 'tags' }, t('tags')),
      h('button', { value: 'remind' }, t('reminders')),
      ...(d.kind === 'pdf' || d.kind === 'images' ? [h('button', { value: 'ocr' }, t('ocrTitle'))] : []),
      h('button', { value: 'lock' }, d.locked && lockEnabled() ? t('unlockDoc') : t('lockDoc')),
      h('button', { value: 'move' }, t('moveToFolder')),
      h('button', { value: 'delete', class: 'danger' }, t('delete')),
      h('button', { value: 'cancel' }, t('cancel')),
    );
    modal(form).closed.then(async (v) => {
      // these read the content: a locked document asks for the PIN first
      if ((v === 'share' || v === 'ocr') && !(await ensureUnlocked(d))) return;
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
      } else if (v === 'pin') {
        d.pinned = !d.pinned;
        if (!d.pinned) delete d.pinned;
        await putDoc(d);
      } else if (v === 'color') {
        await this.colorDialog(d);
      } else if (v === 'tags') {
        await this.tagsDialog(d);
      } else if (v === 'ocr') {
        await (await import('./ocrUi')).ocrDialog(d);
      } else if (v === 'remind') {
        await reminderDialog(d, d.lastPage ?? 0);
      } else if (v === 'lock') {
        await toggleDocLock(d);
      } else if (v === 'move') {
        await this.moveDialog(d);
      } else if (v === 'delete' && confirm(t('confirmDelete'))) {
        await cancelDocReminders(d);
        await deleteDoc(d);
      }
      await this.refresh();
    });
  }

  private async newNotebookDialog() {
    let template: Template = 'lined';
    let paper: PaperColor = 'white';
    let custom: CustomTemplate | null = null;
    let customs = await listTemplates();
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
        const b = h('button', { type: 'button', class: `tpl ${!custom && tp === template ? 'active' : ''}` }, preview(tp, paper), h('span', {}, t(tp)));
        b.addEventListener('click', () => { template = tp; custom = null; renderTpls(); });
        tplRow.append(b);
      }
      // your own templates: a page, a picture or a PDF page
      for (const ct of customs) {
        const img = h('img', { alt: '', class: 'tpl-img' });
        void imageUrl(ct.fileId, ct.mime).then((u) => { img.src = u; }).catch(() => {});
        const del = h('span', { class: 'tpl-del', role: 'button', 'aria-label': t('delete'), title: t('delete') }, '×');
        del.addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm(`${t('deleteTemplate')}: ${ct.name}?`)) return;
          await deleteTemplate(ct.id);
          customs = customs.filter((x) => x.id !== ct.id);
          if (custom?.id === ct.id) custom = null;
          renderTpls();
        });
        const b = h('button', { type: 'button', class: `tpl custom ${custom?.id === ct.id ? 'active' : ''}` },
          h('div', { class: 'tpl-frame' }, img), h('span', {}, ct.name), del);
        b.addEventListener('click', () => { custom = ct; renderTpls(); });
        tplRow.append(b);
      }
      const add = h('button', { type: 'button', class: 'tpl add' }, h('div', { class: 'tpl-frame plus' }, '+'), h('span', {}, t('templateFromFile')));
      add.addEventListener('click', () => {
        const input = h('input', { type: 'file', accept: 'image/*,application/pdf,.pdf', hidden: true });
        input.addEventListener('change', async () => {
          const f = input.files?.[0];
          input.remove();
          if (!f) return;
          try {
            custom = await templateFromFile(f);
            customs = [custom, ...customs];
            renderTpls();
          } catch (err) {
            console.error(err);
            toast(`${t('fileError')}: ${f.name}`);
          }
        });
        document.body.append(input);
        input.click();
      });
      tplRow.append(add);
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
      const ref = custom ? templateRef(custom) : null;
      const page: PageData = ref ? pageFromTemplate(ref) : { id: uid(), w: A4.w, h: A4.h, bg: { kind: 'template', template, paper, rtl: settings.lang === 'ar' }, strokes: [] };
      const now = Date.now();
      const doc: DocMeta = {
        id: uid(), title: title.value.trim() || t('untitled'), kind: 'notebook',
        createdAt: now, updatedAt: now, pageIds: [page.id], template, paper,
        ...(ref ? { customTemplate: ref } : {}),
        ...(this.folderId ? { folderId: this.folderId } : {}),
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
    if (this.folderId) for (const d of docs) { d.folderId = this.folderId; await putDoc(d); }
    await this.refresh();
    // extract the text for search in the background, so it is ready when needed
    void (async () => { for (const d of docs) await indexDoc(d).catch(() => null); })();
    if (unsupported.length) toast(`${t('unsupported')}`);
    else if (failed.length) toast(`${t('fileError')}: ${failed.join(', ')}`);
    else if (docs.length === 1) this.openDoc(docs[0]);
  }

  private setTag(tag: string | null) {
    this.tagFilter = tag;
    writePref(TAG_KEY, tag ?? '');
  }

  /** Tag filter chips and the sort order, under the folders. */
  private renderTools(tags: string[]) {
    this.tools.hidden = !!this.query;
    this.tools.replaceChildren();
    const chips = h('div', { class: 'tag-chips' });
    for (const tg of tags) {
      const on = this.tagFilter === tg;
      const b = h('button', { type: 'button', class: `tag-chip ${on ? 'active' : ''}`, 'aria-pressed': String(on), dir: 'auto' }, `#${tg}`);
      b.addEventListener('click', () => { this.setTag(on ? null : tg); void this.refresh(); });
      chips.append(b);
    }
    const sel = h('select', { class: 'sort-select', 'aria-label': t('sortBy') });
    for (const m of SORT_MODES) sel.append(h('option', { value: m, selected: m === this.sortMode }, t(`sort_${m}`)));
    sel.addEventListener('change', () => {
      this.sortMode = sel.value as SortMode;
      writePref(SORT_KEY, this.sortMode);
      void this.refresh();
    });
    this.tools.append(chips, h('label', { class: 'sort' }, h('span', {}, t('sortBy')), sel));
  }

  private async colorDialog(d: DocMeta) {
    const form = h('form', { method: 'dialog', class: 'menu' }, h('h2', {}, t('colorLabel')));
    const row = h('div', { class: 'color-row' });
    for (const c of DOC_COLORS) {
      row.append(h('button', { value: c, class: `color-swatch c-${c} ${d.color === c ? 'active' : ''}`, 'aria-label': t(`color_${c}`), title: t(`color_${c}`) }));
    }
    form.append(row, h('button', { value: 'none' }, t('noColor')), h('button', { value: 'cancel' }, t('cancel')));
    const v = await modal(form).closed;
    if (!v || v === 'cancel') return;
    if (v === 'none') delete d.color;
    else d.color = v as DocColor;
    await putDoc(d);
  }

  /** Tick existing tags or type new ones (separated by commas). */
  private async tagsDialog(d: DocMeta) {
    const chosen = new Set(d.tags ?? []);
    const existing = allTags(await listDocs(), document.documentElement.lang);
    const row = h('div', { class: 'tag-chips wrap' });
    for (const tg of existing) {
      const b = h('button', { type: 'button', class: `tag-chip ${chosen.has(tg) ? 'active' : ''}`, dir: 'auto' }, `#${tg}`);
      b.addEventListener('click', () => {
        if (chosen.has(tg)) chosen.delete(tg);
        else chosen.add(tg);
        b.classList.toggle('active', chosen.has(tg));
      });
      row.append(b);
    }
    const input = h('input', { type: 'text', class: 'text', placeholder: t('newTagsHint'), dir: 'auto' });
    const form = h('form', { method: 'dialog', class: 'new-nb' },
      h('h2', {}, t('tags')),
      existing.length ? row : '',
      h('label', { class: 'row col' }, h('span', {}, t('newTags')), input),
      h('div', { class: 'actions' },
        h('button', { value: 'cancel', type: 'submit' }, t('cancel')),
        h('button', { value: 'save', type: 'submit', class: 'primary' }, t('save')),
      ),
    );
    if ((await modal(form).closed) !== 'save') return;
    const tags = cleanTags([...chosen, ...input.value.split(/[,،;]/)]);
    if (tags.length) d.tags = tags;
    else delete d.tags;
    await putDoc(d);
  }

  private setFolder(id: string | null) {
    this.folderId = id;
    try { localStorage.setItem(FOLDER_KEY, id ?? ''); } catch { /* private mode */ }
  }

  private renderFolders(all: DocMeta[]) {
    this.folderBar.hidden = !!this.query;
    this.folderBar.replaceChildren();
    const chip = (label: string, id: string | null, count: number) => {
      const b = h('button', { type: 'button', class: `folder-chip ${this.folderId === id ? 'active' : ''}`, 'aria-pressed': String(this.folderId === id) });
      if (id) {
        const f = this.folders.find((x) => x.id === id);
        b.innerHTML = f?.locked && lockEnabled() ? icons.lock : icons.folder;
      }
      b.append(h('span', {}, label), h('small', {}, String(count)));
      b.addEventListener('click', () => {
        const f = id ? this.folders.find((x) => x.id === id) : undefined;
        if (f && this.folderId === id) { this.folderMenu(f); return; }
        void (async () => {
          if (f && folderHidden(f) && !(await ensureFolderUnlocked(f))) return;
          this.setFolder(id);
          await this.refresh();
        })();
      });
      return b;
    };
    this.folderBar.append(chip(t('allDocs'), null, all.length));
    for (const f of this.folders) this.folderBar.append(chip(f.name, f.id, all.filter((d) => d.folderId === f.id).length));
    const add = h('button', { type: 'button', class: 'folder-chip add' });
    add.innerHTML = icons.plus;
    add.append(h('span', {}, t('newFolder')));
    add.addEventListener('click', async () => {
      const name = prompt(t('folderName'))?.trim();
      if (!name) return;
      const f: Folder = { id: uid(), name, createdAt: Date.now() };
      await putFolder(f);
      this.setFolder(f.id);
      await this.refresh();
    });
    this.folderBar.append(add);
  }

  /** Tapping the open folder again: rename or delete it. */
  private folderMenu(f: Folder) {
    const form = h('form', { method: 'dialog', class: 'menu' },
      h('h2', {}, f.name),
      h('button', { value: 'rename' }, t('renameFolder')),
      h('button', { value: 'lock' }, f.locked && lockEnabled() ? t('unlockFolder') : t('lockFolder')),
      h('button', { value: 'delete', class: 'danger' }, t('deleteFolder')),
      h('button', { value: 'cancel' }, t('cancel')),
    );
    modal(form).closed.then(async (v) => {
      if (v === 'rename') {
        const name = prompt(t('folderName'), f.name)?.trim();
        if (name) await putFolder({ ...f, name });
      } else if (v === 'lock') {
        await toggleFolderLock(f);
      } else if (v === 'delete' && confirm(t('confirmDeleteFolder'))) {
        await deleteFolder(f.id);
        await reconcileAll(); // its documents left a locked folder: decrypt them (if the key is open)
        this.setFolder(null);
      }
      await this.refresh();
    });
  }

  private async moveDialog(d: DocMeta) {
    const form = h('form', { method: 'dialog', class: 'menu' }, h('h2', {}, t('moveToFolder')));
    const opt = (id: string, label: string, current: boolean) => {
      const b = h('button', { value: id, class: `menu-item ${current ? 'active' : ''}` });
      b.innerHTML = id === '-' ? icons.file : icons.folder;
      b.append(h('span', {}, label));
      return b;
    };
    form.append(opt('-', t('noFolder'), !d.folderId));
    for (const f of this.folders) form.append(opt(f.id, f.name, d.folderId === f.id));
    form.append(h('button', { value: 'cancel' }, t('cancel')));
    const v = await modal(form).closed;
    if (!v || v === 'cancel') return;
    if (v === '-') delete d.folderId;
    else d.folderId = v;
    await putDoc(d);
  }

  /** Searches inside the PDFs and the clips; prepares the text of PDFs that were never searched first. */
  private async runContentSearch() {
    const seq = ++this.searchSeq;
    const q = this.query;
    if (q.length < 2) { this.results.replaceChildren(); return; }
    const all = await listDocs();
    const todo = await unindexed(all);
    for (let i = 0; i < todo.length; i++) {
      if (seq !== this.searchSeq) return;
      this.results.replaceChildren(h('p', { class: 'hint' }, `${t('indexing')} ${i + 1} / ${todo.length} · ${todo[i].title}`));
      await indexDoc(todo[i]).catch((e) => console.warn('index', todo[i].title, e));
    }
    // the text of locked documents stays hidden from search until they are opened
    const hits = await searchContent(q, all.filter((d) => !isHidden(d)));
    if (seq !== this.searchSeq) return;
    this.renderHits(hits);
  }

  private renderHits(hits: Hit[]) {
    const titleHits = this.grid.querySelectorAll('.card').length;
    this.results.replaceChildren(h('h2', {}, t('foundInContent'), h('small', {}, ` · ${hits.length}`)));
    if (!hits.length) {
      this.results.append(h('p', { class: 'hint' }, titleHits ? t('noResults') : `${t('noResults')} – „${this.query}“`));
      return;
    }
    const list = h('div', { class: 'hit-list' });
    for (const hit of hits) {
      const row = h('button', { type: 'button', class: 'hit' },
        h('div', { class: 'hit-head' },
          h('b', {}, hit.doc.title),
          h('span', { class: `badge ${hit.kind === 'clip' ? 'notebook' : 'pdf'}` }, hit.kind === 'clip' ? t('inClip') : `${t('page')} ${hit.page + 1}`),
          hit.count > 1 ? h('small', {}, `${hit.count} ${t('hits')}`) : '',
        ),
        h('p', { class: 'hit-text', dir: 'auto' }, hit.snippet.before, h('mark', {}, hit.snippet.match), hit.snippet.after),
      );
      row.addEventListener('click', () => this.openDoc({ ...hit.doc, lastPage: hit.page }));
      list.append(row);
    }
    this.results.append(list);
  }
}

const FOLDER_KEY = 'alnai-folder';
const TAG_KEY = 'alnai-tag';
const SORT_KEY = 'alnai-sort';
function readPref(k: string): string {
  try { return localStorage.getItem(k) ?? ''; } catch { return ''; }
}
function writePref(k: string, v: string) {
  try { localStorage.setItem(k, v); } catch { /* private mode */ }
}
function readFolder(): string | null {
  try { return localStorage.getItem(FOLDER_KEY) || null; } catch { return null; }
}
