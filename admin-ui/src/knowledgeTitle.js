// Името на знание в списъците: заглавието, иначе началото на текста
export function knowledgeTitle(doc) {
  if (doc.title) {
    return doc.title
  }
  if (!doc.text) {
    return '(без заглавие)'
  }
  return doc.text.length > 80 ? `${doc.text.slice(0, 80)}…` : doc.text
}
