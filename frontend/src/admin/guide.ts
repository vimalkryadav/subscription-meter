export interface GuideSection { id: string; title: string; body: string }
export function guideSections(markdown: string): GuideSection[] {
  return markdown.split(/(?=^## )/m).map((body, index) => {
    const heading = body.match(/^## (.+)$/m)?.[1];
    const title = heading || 'Introduction';
    return { id: heading ? title.toLowerCase().replace(/[^a-z0-9\s-]/g, '').trim().replace(/\s+/g, '-') : 'introduction',
      title, body: index === 0 ? body.replace(/^# .+\n/, '') : body };
  });
}
export function filterGuide(sections: GuideSection[], query: string): GuideSection[] {
  const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  return sections.filter(section => words.every(word => section.body.toLowerCase().includes(word)));
}
