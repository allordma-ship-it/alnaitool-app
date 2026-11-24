import { Capacitor } from '@capacitor/core';
import { putDoc, getDoc, listDocs } from './db';
import { nextFire, activeReminders, newReminderId, defaultTime, toLocalInput } from './reminderTime';
import type { DocMeta, Reminder } from './types';
import { t } from './i18n';
import { h, modal, toast } from './ui';
import { icons } from './icons';

const native = () => Capacitor.isNativePlatform();
const LN = () => import('@capacitor/local-notifications').then((m) => m.LocalNotifications);

async function allowed(): Promise<boolean> {
  const ln = await LN();
  let p = await ln.checkPermissions();
  if (p.display !== 'granted') p = await ln.requestPermissions();
  return p.display === 'granted';
}

async function schedule(doc: DocMeta, r: Reminder) {
  const ln = await LN();
  const first = new Date(r.at);
  const when =
    r.repeat === 'daily' ? { on: { hour: first.getHours(), minute: first.getMinutes() }, allowWhileIdle: true }
    : r.repeat === 'weekly' ? { on: { weekday: first.getDay() + 1, hour: first.getHours(), minute: first.getMinutes() }, allowWhileIdle: true }
    : { at: first, allowWhileIdle: true };
  await ln.schedule({
    notifications: [{
      id: r.id,
      title: doc.title,
      body: r.note || `${t('page')} ${r.page + 1}`,
      schedule: when,
      extra: { docId: doc.id, page: r.page },
    }],
  });
}

async function cancel(ids: number[]) {
  if (!native() || !ids.length) return;
  try { await (await LN()).cancel({ notifications: ids.map((id) => ({ id })) }); } catch { /* already gone */ }
}

/** Removes every reminder of a document (it is being deleted). */
export async function cancelDocReminders(d: DocMeta) {
  await cancel((d.reminders ?? []).map((r) => r.id));
}

/**
 * At start: forget reminders that have rung for the last time, and schedule again any that Android does not know
 * (after a reinstall, or a backup restored on another device).
 */
export async function reconcileReminders() {
  if (!native()) return;
  try {
    const ln = await LN();
    if ((await ln.checkPermissions()).display !== 'granted') return;
    const pending = new Set((await ln.getPending()).notifications.map((n) => n.id));
    for (const d of await listDocs()) {
      if (!d.reminders?.length) continue;
      const keep = activeReminders(d.reminders);
      for (const r of keep) if (!pending.has(r.id)) await schedule(d, r);
      if (keep.length !== d.reminders.length) {
        if (keep.length) d.reminders = keep;
        else delete d.reminders;
        await putDoc(d);
      }
    }
  } catch (e) {
    console.warn('reminders', e);
  }
}

/** Tapping a notification opens its document at its page. */
export async function onReminderTap(open: (docId: string, page: number) => void) {
  if (!native()) return;
  (await LN()).addListener('localNotificationActionPerformed', (a) => {
    const x = a.notification.extra as { docId?: string; page?: number } | undefined;
    if (x?.docId) open(x.docId, x.page ?? 0);
  });
}

const fmt = (ms: number) => new Date(ms).toLocaleString(document.documentElement.lang, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

/** List, add and delete the reminders of a document. `page` is where a new reminder will open it. */
export async function reminderDialog(docIn: DocMeta, page: number): Promise<void> {
  const doc = (await getDoc(docIn.id)) ?? docIn;
  const list = h('div', { class: 'rem-list' });
  const render = () => {
    list.replaceChildren();
    for (const r of activeReminders(doc.reminders)) {
      const del = h('button', { type: 'button', class: 'icon-btn small', title: t('delete'), 'aria-label': t('delete') });
      del.innerHTML = icons.trash;
      del.addEventListener('click', async () => {
        await cancel([r.id]);
        doc.reminders = (doc.reminders ?? []).filter((x) => x.id !== r.id);
        if (!doc.reminders.length) delete doc.reminders;
        await putDoc(doc);
        Object.assign(docIn, { reminders: doc.reminders });
        render();
      });
      const rep = r.repeat === 'daily' ? ` · ${t('repeatDaily')}` : r.repeat === 'weekly' ? ` · ${t('repeatWeekly')}` : '';
      list.append(h('div', { class: 'rem-item' },
        h('div', {}, h('b', {}, fmt(nextFire(r)!)), h('small', {}, `${t('page')} ${r.page + 1}${rep}${r.note ? ` · ${r.note}` : ''}`)),
        del));
    }
    if (!list.children.length) list.append(h('p', { class: 'hint' }, t('noReminders')));
  };
  render();
  const when = h('input', { type: 'datetime-local', class: 'text', value: toLocalInput(defaultTime()) });
  const repeat = h('select', { class: 'text' },
    h('option', { value: 'none' }, t('repeatNone')),
    h('option', { value: 'daily' }, t('repeatDaily')),
    h('option', { value: 'weekly' }, t('repeatWeekly')));
  const note = h('input', { type: 'text', class: 'text', placeholder: t('reminderNoteHint'), dir: 'auto', maxlength: 120 });
  const form = h('form', { method: 'dialog', class: 'new-nb rem-dlg' },
    h('h2', {}, t('reminders')),
    h('p', { class: 'hint' }, `${doc.title} · ${t('page')} ${page + 1}`),
    list,
    h('h3', {}, t('newReminder')),
    h('label', { class: 'row col' }, h('span', {}, t('reminderWhen')), when),
    h('label', { class: 'row col' }, h('span', {}, t('reminderRepeat')), repeat),
    h('label', { class: 'row col' }, h('span', {}, t('reminderNote')), note),
    native() ? '' : h('p', { class: 'hint' }, t('reminderNativeOnly')),
    h('div', { class: 'actions' },
      h('button', { type: 'submit', value: 'cancel', formnovalidate: true }, t('close')),
      h('button', { type: 'submit', value: 'add', class: 'primary', disabled: !native() }, t('addReminder'))),
  );
  const { dlg, closed } = modal(form);
  form.addEventListener('submit', async (e) => {
    const btn = (e as SubmitEvent).submitter as HTMLButtonElement | null;
    if (btn?.value !== 'add') return;
    e.preventDefault();
    const atMs = new Date(when.value).getTime();
    const r: Reminder = { id: newReminderId(), at: atMs, page, note: note.value.trim(), repeat: repeat.value as Reminder['repeat'] };
    if (!Number.isFinite(atMs) || nextFire(r) == null) { toast(t('reminderPast')); return; }
    try {
      if (!(await allowed())) { toast(t('reminderNoPermission')); return; }
      await schedule(doc, r);
    } catch (err) {
      console.error(err);
      toast(String(err));
      return;
    }
    doc.reminders = [...(doc.reminders ?? []), r];
    await putDoc(doc);
    Object.assign(docIn, { reminders: doc.reminders });
    toast(`${t('reminderSet')} ${fmt(nextFire(r)!)}`);
    note.value = '';
    render();
  });
  await closed;
  void dlg;
}
