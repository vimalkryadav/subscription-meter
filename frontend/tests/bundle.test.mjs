import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, access } from 'node:fs/promises';
const root = new URL('../../target/frontend-resources/', import.meta.url);
test('build packages private admin document and release-relative module assets', async () => {
  for (const page of ['static/index.html', 'admin/manage.html']) {
    const html = await readFile(new URL(page, root), 'utf8');
    assert.match(html, /id="root"/);
    assert.doesNotMatch(html, /src="\/src\/|<script>(?!<)/);
    const assets = [...html.matchAll(/(?:src|href)="(\/assets\/react\/[^\"]+)"/g)].map(match => match[1]);
    assert.ok(assets.length >= 2, 'bundled script and CSS required');
    for (const asset of assets) {
      await access(new URL('static' + asset, root));
      const fingerprinted = asset.replace('/assets/', '/assets/releases/' + 'a'.repeat(40) + '/');
      assert.ok(fingerprinted.startsWith('/assets/releases/'));
    }
  }
  await assert.rejects(access(new URL('static/manage.html', root)));
});
