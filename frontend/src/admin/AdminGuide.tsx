import { useState } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import adminGuide from '../../../docs/admin-guide.md?raw';
import mcpGuide from '../../../docs/mcp-guide.md?raw';
import { Heading, Notice, Select } from './ui';
import { filterGuide, guideSections } from './guide';
import './guide.css';

const books = { admin: { title: 'Administrator guide', source: adminGuide, sections: guideSections(adminGuide) },
  mcp: { title: 'MCP setup & tools', source: mcpGuide, sections: guideSections(mcpGuide) } };
type Book = keyof typeof books;
export default function AdminGuide() {
  const params = new URLSearchParams(location.search);
  const [book, setBook] = useState<Book>(params.get('guide') === 'mcp' ? 'mcp' : 'admin');
  const [chapter, setChapter] = useState(params.get('chapter') || 'introduction');
  const [query, setQuery] = useState('');
  const current = books[book], visible = filterGuide(current.sections, query);
  const selected = visible.find(section => section.id === chapter) || visible[0];
  function navigate(nextBook: Book, nextChapter: string) {
    setBook(nextBook); setChapter(nextChapter);
    const url = new URL(location.href); url.searchParams.set('guide', nextBook); url.searchParams.set('chapter', nextChapter);
    history.replaceState({}, '', url);
  }
  function download() {
    const url = URL.createObjectURL(new Blob([current.source], { type: 'text/markdown;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = book + '-guide.md'; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <><Heading title="Admin guide & MCP" subtitle="Operating procedures, troubleshooting and AI-client setup."><button onClick={download}>Download this guide</button></Heading>
    <div className="cu-toolbar"><Select label="Guide" value={book} onChange={value => { setQuery(''); navigate(value as Book, 'introduction'); }} items={[{ value: 'admin', label: books.admin.title }, { value: 'mcp', label: books.mcp.title }]} />
      <label>Search guide<input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="Quota, approvals, MCP, backups…" /></label>
      <span className="cu-muted" aria-live="polite">{visible.length} of {current.sections.length} sections</span>{query && <button onClick={() => setQuery('')}>Clear search</button>}</div>
    <div className="cu-guide-layout"><nav aria-label="Guide sections" className="cu-guide-toc">{visible.map(section => <button key={section.id} aria-current={selected?.id === section.id ? 'page' : undefined} onClick={() => { navigate(book, section.id); requestAnimationFrame(() => document.getElementById('guide-article')?.focus()); }}>{section.title}</button>)}</nav>
      <article className="cu-panel cu-guide-prose" id="guide-article" tabIndex={-1} aria-label={selected?.title || 'Guide search results'}>
        {selected ? <><p className="cu-eyebrow">{current.title} · {current.sections.findIndex(section => section.id === selected.id) + 1} / {current.sections.length}</p>
          <Markdown remarkPlugins={[remarkGfm]} skipHtml components={{
            a: ({ children, href }) => <a href={href} target={href?.startsWith('https://') ? '_blank' : undefined} rel="noreferrer">{children}</a>,
            table: ({ children }) => <div className="cu-table-scroll" tabIndex={0} role="region" aria-label="Guide reference table"><table>{children}</table></div>,
          }}>{selected.body}</Markdown>
          <div className="cu-bar cu-guide-next">{visible.indexOf(selected) > 0 && <button onClick={() => navigate(book, visible[visible.indexOf(selected) - 1].id)}>Previous section</button>}{visible.indexOf(selected) < visible.length - 1 && <button onClick={() => navigate(book, visible[visible.indexOf(selected) + 1].id)}>Next section</button>}</div>
        </> : <Notice>No sections match this search. Try fewer words or choose the other guide.</Notice>}
      </article></div>
  </>;
}
