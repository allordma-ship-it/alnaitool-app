// Drives the real Wanees web build like a user and records screenshots + video.
const { chromium } = require('playwright');
const fs = require('fs');

const OUT = process.env.OUT || 'demo-out';
const APP = process.env.APP_URL || 'http://localhost:5555/';
fs.mkdirSync(OUT, { recursive: true });
let shot = 0;

(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
  const ctx = await browser.newContext({
    viewport: { width: 412, height: 870 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    locale: 'ar-SY', timezoneId: 'Asia/Damascus',
    recordVideo: { dir: OUT, size: { width: 412, height: 870 } },
  });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('pageerror:', e.message.slice(0, 200)));

  const sem = () => page.evaluate(() => { const p = document.querySelector('flt-semantics-placeholder'); if (p) p.click(); });
  const wait = ms => page.waitForTimeout(ms);
  const snap = async (name, settle = 1200) => {
    await wait(settle);
    const file = `${OUT}/${String(++shot).padStart(2, '0')}-${name}.png`;
    await page.screenshot({ path: file });
    console.log('shot', file);
  };
  // Click the innermost semantics node whose text contains `text`.
  const tap = async (text, { exact = false, nth = 0, role } = {}) => {
    const box = await page.evaluate(({ text, exact, nth, role }) => {
      const nodes = [...document.querySelectorAll('flt-semantics')].filter(e => {
        const t = (e.getAttribute('aria-label') || e.textContent || '').trim();
        if (role && e.getAttribute('role') !== role) return false;
        return exact ? t === text : t.includes(text);
      });
      const inner = nodes.filter(n => !nodes.some(o => o !== n && n.contains(o)));
      const el = inner[nth];
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    }, { text, exact, nth, role });
    if (!box) {
      const all = await page.evaluate(() => [...document.querySelectorAll('flt-semantics')].map(e => (e.getAttribute('aria-label') || e.textContent || '').trim()).filter(Boolean).slice(0, 60));
      throw new Error(`not found: ${text}\nvisible: ${all.join(' | ')}`);
    }
    await page.mouse.click(box.x, box.y);
    await wait(700);
  };
  const type = async (label, value) => {
    const input = page.locator(`input[aria-label="${label}"], textarea[aria-label="${label}"]`).first();
    await input.waitFor({ state: 'attached', timeout: 15000 });
    const b = await input.boundingBox();
    await page.mouse.click(b.x + b.width / 2, b.y + b.height / 2);
    await wait(500);
    await page.keyboard.type(value, { delay: 40 });
    await wait(400);
  };
  const typeFocused = async value => { await page.keyboard.type(value, { delay: 60 }); };
  const scroll = async (dy) => { await page.mouse.move(206, 500); await page.mouse.wheel(0, dy); await wait(900); };

  // 1. Login
  await page.goto(APP, { waitUntil: 'load' });
  await wait(9000); await sem(); await wait(1500);
  await snap('login');
  await type('البريد الإلكتروني', 'ahmad@wanees.demo');
  await type('كلمة المرور', 'wanees2026');
  await tap('تسجيل الدخول', { role: 'button', exact: true });
  await wait(2500); await sem();

  // 2. Search form
  await snap('search');
  await tap('الانطلاق من');
  await wait(1200); await sem(); await type('مدينة أو عنوان أو محطة', 'حمص'); await wait(3500); await sem();
  await snap('place-search');
  await tap('حمص, سوريا');
  await wait(800);
  await tap('الوجهة');
  await wait(1200); await sem(); await type('مدينة أو عنوان أو محطة', 'حماة'); await wait(3500); await sem();
  await tap('حماة, سوريا');
  await wait(800);
  // Pick tomorrow in the date picker.
  await tap('التاريخ');
  await wait(1200); await sem();
  const tomorrow = new Date(Date.now() + 24 * 3600 * 1000).toLocaleString('en-US', { timeZone: 'Asia/Damascus', day: 'numeric' });
  await page.evaluate(() => {}); // keep semantics fresh
  const dayClicked = await page.evaluate((d) => {
    const nodes = [...document.querySelectorAll('flt-semantics')].filter(e => {
      const t = (e.getAttribute('aria-label') || e.textContent || '').trim();
      return t === d || t.startsWith(d + ',') || t.startsWith(d + '،') || t.startsWith(d + ' ');
    });
    const el = nodes.find(n => !nodes.some(o => o !== n && n.contains(o)));
    if (!el) return false;
    const r = el.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  }, tomorrow);
  if (!dayClicked) throw new Error('day not found ' + tomorrow);
  await page.mouse.click(dayClicked.x, dayClicked.y); await wait(600);
  await snap('date-picker', 300);
  await tap('حسنًا').catch(async () => tap('موافق'));
  await wait(800); await sem();
  await snap('search-filled');

  // 3. Results
  await tap('ابحث', { role: 'button' });
  await wait(4000); await sem();
  await snap('results');

  // 4. Ride detail with route map
  await tap('سامر');
  await wait(5000); await sem();
  await snap('ride-detail-map', 2500);
  await scroll(600);
  await snap('ride-detail-book');

  // 5. Book (instant booking, cash)
  await tap('احجز', { role: 'button', exact: true });
  await wait(4000); await sem();
  await snap('booking-confirmed', 2500);
  await scroll(500);
  await snap('booking-cash');

  // 6. Driver dashboard: Ahmad's own ride with two requests
  await page.goBack(); await wait(2000); await sem();
  await tap('رحلاتي', { role: 'tab' }).catch(() => tap('رحلاتي'));
  await wait(2500); await sem();
  await snap('trips-passenger');
  await tap('كسائق');
  await wait(2500); await sem();
  await snap('trips-driver');
  await tap('طلبان').catch(() => tap('حلب'));
  await wait(4000); await sem();
  await snap('driver-ride', 2500);
  await scroll(500);
  await snap('driver-requests');
  await tap('قبول', { role: 'button', exact: true });
  await wait(2500); await sem();
  await snap('driver-accepted');

  // 7. Offer a ride: route preview + suggested price
  await page.goBack(); await wait(1500); await sem();
  await tap('نشر رحلة');
  await wait(1500); await sem();
  await tap('الانطلاق', { exact: true });
  await wait(1200); await sem(); await type('مدينة أو عنوان أو محطة', 'اللاذقية'); await wait(3500); await sem();
  await tap('اللاذقية, ساحة اليمن');
  await wait(800);
  await tap('الوجهة', { exact: true });
  await wait(1200); await sem(); await type('مدينة أو عنوان أو محطة', 'حلب'); await wait(3500); await sem();
  await tap('حلب, سوريا');
  await wait(6000); await sem();
  await snap('publish-preview', 2500);
  await scroll(700);
  await snap('publish-price');

  // 8. Profile
  await tap('حسابي');
  await wait(2000); await sem();
  await snap('profile');

  // 9. Same app in English (LTR) for comparison
  await scroll(800);
  await tap('English');
  await wait(1500); await scroll(-2000);
  await snap('profile-english');
  await tap('Search');
  await wait(1500);
  await snap('search-english');

  await ctx.close();
  await browser.close();
  console.log('done');
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
