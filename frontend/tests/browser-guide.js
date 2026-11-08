// Invoke with an authenticated Playwright page. Reads documentation only; no admin mutations.
async (page) => {
  await page.getByRole('navigation', { name: 'Admin navigation' }).getByRole('link', { name: 'Admin guide & MCP', exact: true }).click();
  await page.getByRole('heading', { name: 'Admin guide & MCP', exact: true }).waitFor();
  const results = [];
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    for (const book of ['Administrator guide', 'MCP setup & tools']) {
      const select = page.locator('.cu-select-field').filter({ hasText: 'Guide' });
      await select.getByRole('button').first().click();
      await page.getByRole('option', { name: book, exact: true }).click();
      const navigation = page.getByRole('navigation', { name: 'Guide sections' });
      const titles = await navigation.getByRole('button').allTextContents();
      if (titles.length < 15) throw new Error('Guide chapters missing');
      for (const title of titles) {
        await navigation.getByRole('button', { name: title, exact: true }).click();
        await page.getByRole('article', { name: title, exact: true }).waitFor();
        const layout = await page.evaluate(() => ({ overflow: document.documentElement.scrollWidth > innerWidth, nativeSelects: document.querySelectorAll('select').length }));
        if (layout.overflow || layout.nativeSelects) throw new Error('Guide layout failed: ' + JSON.stringify({ width, book, title, ...layout }));
        results.push({ width, book, title });
      }
    }
  }
  const search = page.getByRole('searchbox', { name: 'Search guide' });
  await search.fill('CSRF');
  if (!await page.getByRole('navigation', { name: 'Guide sections' }).getByRole('button').count()) throw new Error('Guide search missing matches');
  await search.fill('no-such-guide-section-xyz');
  await page.getByText('No sections match this search. Try fewer words or choose the other guide.', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Clear search', exact: true }).click();
  await page.getByRole('navigation', { name: 'Guide sections' }).getByRole('button', { name: 'Configure the MCP client', exact: true }).click();
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('article', { name: 'Configure the MCP client', exact: true }).waitFor();
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download this guide', exact: true }).click();
  if ((await downloaded).suggestedFilename() !== 'mcp-guide.md') throw new Error('Wrong guide download');
  return { checked: results.length, search: true, deepLink: true, download: true, results };
}
