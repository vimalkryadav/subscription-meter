import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {stripTypeScriptTypes} from 'node:module';
const source=await readFile(new URL('../src/admin/guide.ts',import.meta.url),'utf8');
const {guideSections,filterGuide}=await import('data:text/javascript;base64,'+Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
test('both guides have unique navigable sections and searchable procedures',async()=>{
 for(const name of ['admin','mcp']){
  const raw=await readFile(new URL('../../docs/'+name+'-guide.md',import.meta.url),'utf8');
  const sections=guideSections(raw);assert.ok(sections.length>=15);assert.equal(new Set(sections.map(s=>s.id)).size,sections.length);
  assert.ok(filterGuide(sections,'quota').length>0);assert.ok(filterGuide(sections,'CSRF').length>0);
  assert.equal(filterGuide(sections,'unfindable-quota-xyz').length,0);assert.equal(filterGuide(sections,'').length,sections.length);
 }
});
