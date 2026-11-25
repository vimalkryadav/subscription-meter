// Run through Playwright's page executor after an administrator signs in.
// No credentials, production mutations, or payload contents are recorded here.
async (page) => {
  const views = [
    ['Overview', '/admin/usage-overview?'],
    ['Overview', '/admin/metrics', 'Metrics'],
    ['Overview', '/admin/sessions', 'Sessions'],
    ['Request explorer', '/admin/request-search?'],
    ['Usage & value', '/admin/accounting?'],
    ['Usage & value', '/admin/usage-overview?', 'Leaderboard'],
    ['Members & keys', '/admin/members'],
    ['Members & keys', '/admin/key-requests', 'Access requests'],
    ['Members & keys', '/admin/accounts?', 'Accounts & sessions'],
    ['Operations & setup', '/admin/operations'],
    ['Operations & setup', '/admin/status', 'Connection'],
  ];
  await page.getByRole('navigation', { name: 'Admin navigation' }).getByRole('link', { name: 'Operations & setup', exact: true }).waitFor({ state: 'attached' });
  await page.evaluate(() => { location.hash = '#view/connection'; });
  await page.getByRole('heading', { name: 'Connection', exact: true }).waitFor();
  const results = [];
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    for (const [view, path, subview] of views) {
      const waitForView = () => page.waitForResponse(value => (new URL(value.url()).pathname + new URL(value.url()).search).startsWith(path) && value.request().method() === 'GET');
      const menu = page.getByRole('button', { name: 'Menu', exact: true });
      if (await menu.isVisible()) await menu.click();
      const sidebarLink = page.getByRole('navigation', { name: 'Admin navigation' }).getByRole('link', { name: view, exact: true });
      let response = subview === 'Leaderboard' ? undefined : waitForView();
      await sidebarLink.click();
      if (!response) response = waitForView();
      if (subview) await page.getByRole('main').getByRole('link', { name: subview, exact: true }).click();
      if ((await response).status() !== 200) throw new Error(`${view}: reporting API failed`);
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const result = await page.evaluate(({ width, view }) => {
        const header = document.querySelector('.cu-topbar').getBoundingClientRect();
        const sidebar = document.querySelector('.cu-sidebar').getBoundingClientRect();
        return {
          width, view,
          overflow: document.documentElement.scrollWidth > innerWidth,
          bodyMargin: getComputedStyle(document.body).margin,
          aligned: width <= 760 || Math.abs(header.bottom - sidebar.top) < 1,
          clippedLabels: [...document.querySelectorAll('.cu-chart svg text')].filter(text => {
            const box = text.getBBox(), svg = text.ownerSVGElement.viewBox.baseVal;
            return box.x < 0 || box.x + box.width > svg.width + 1;
          }).length,
          nativeSelects: document.querySelectorAll('main select:not(.workspace-pagination select)').length,
        };
      }, { width, view });
      if (result.overflow || result.bodyMargin !== '0px' || !result.aligned || result.clippedLabels || result.nativeSelects)
        throw new Error('Layout regression: ' + JSON.stringify(result));
      results.push(result);
    }
  }
  return { checked: results.length, results };
}
