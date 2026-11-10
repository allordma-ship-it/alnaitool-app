type Attrs = Record<string, string | number | boolean | undefined>;

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: (Node | string)[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === false) continue;
    if (k === 'value' && 'value' in el) (el as HTMLInputElement).value = String(v);
    else if (k === 'checked' && 'checked' in el) (el as HTMLInputElement).checked = Boolean(v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, String(v));
  }
  el.append(...children);
  return el;
}

let toastTimer = 0;
export function toast(msg: string) {
  let el = document.querySelector<HTMLDivElement>('.toast');
  if (!el) {
    el = h('div', { class: 'toast', role: 'status' });
    document.body.append(el);
  }
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => el!.classList.remove('show'), 2600);
}

/** Opens a modal <dialog>; resolves when it closes. */
export function modal(content: HTMLElement, cls = ''): { dlg: HTMLDialogElement; closed: Promise<string> } {
  const dlg = h('dialog', { class: `modal ${cls}` });
  dlg.append(content);
  document.body.append(dlg);
  const closed = new Promise<string>((res) => {
    dlg.addEventListener('close', () => {
      res(dlg.returnValue);
      dlg.remove();
    });
  });
  dlg.addEventListener('click', (e) => {
    if (e.target === dlg) dlg.close('cancel');
  });
  dlg.showModal();
  return { dlg, closed };
}
