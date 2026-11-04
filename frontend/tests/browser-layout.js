// Run through Playwright's page executor after an administrator signs in.
// No credentials, production mutations, or payload contents are recorded here.
async (page) => {
  const views = [
    ['Metrics', '/admin/metrics'],
    ['Request explorer', '/admin/request-search?'],
    ['Usage & value', '/admin/accounting?'],
    ['Members & keys', '/admin/members'],
    ['Access requests', '/admin/key-requests'],
    ['Accounts & sessions', '/admin/accounts?'],
    ['Connection', '/admin/status'],
    ['Operations', '/admin/operations'],
  ];
  await page.getByRole('navigation', { name: 'Admin navigation' }).getByRole('link', { name: 'Connection', exact: true }).click();
  await page.getByRole('heading', { name: 'Connection', exact: true }).waitFor();
  const results = [];
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    for (const [view, path] of views) {
      const response = page.waitForResponse(value => value.url().includes(path) && value.request().method() === 'GET');
      await page.getByRole('navigation', { name: 'Admin navigation' }).getByRole('link', { name: view, exact: true }).click();
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
          nativeSelects: document.querySelectorAll('main select').length,
        };
      }, { width, view });
      if (result.overflow || result.bodyMargin !== '0px' || !result.aligned || result.clippedLabels || result.nativeSelects)
        throw new Error('Layout regression: ' + JSON.stringify(result));
      results.push(result);
    }
  }
  return { checked: results.length, results };
}
