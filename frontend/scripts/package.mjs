import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
const source = new URL('../../target/frontend-build/', import.meta.url);
const target = new URL('../../target/frontend-resources/', import.meta.url);
await rm(target, { recursive: true, force: true });
await mkdir(new URL('static/assets/', target), { recursive: true });
await mkdir(new URL('admin/', target), { recursive: true });
await cp(new URL('assets/react', source), new URL('static/assets/react', target), { recursive: true });
for (const [input, output] of [['index.html','static/index.html'],['manage.html','admin/manage.html']]) {
  const html = (await readFile(new URL(input, source), 'utf8')).replaceAll('./assets/', '/assets/');
  await writeFile(new URL(output, target), html);
}
