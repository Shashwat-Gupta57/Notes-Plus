// The notes grid's layout: the phone's StaggeredGridLayoutManager and the desktop's MasonryPane.
// Equal columns, each card dropped into the shortest one, in order: so notes read along the rows
// (the newest top left, the next one to its right), never down one column and then the next, which is
// what CSS `columns` does.
//
// One pass reads every card's height (a single layout), the next writes every position. A
// ResizeObserver lays the grid out again when a card changes height (a picture loading, a font) or
// the window changes width; a MutationObserver when the cards are replaced, before the next paint.

/** Columns at this window width: at most `count`, each at least `min` wide (as `columns: count min` did). */
function columnsFor(width) {
  const vw = window.innerWidth;
  const gap = vw <= 640 ? 10 : 12;
  const [count, min] = vw <= 640 ? [2, 150] : vw >= 1100 ? [4, 240] : vw >= 700 ? [3, 220] : [2, 180];
  const n = Math.max(1, Math.min(count, Math.floor((width + gap) / (min + gap))));
  return { n, gap };
}

/** Lays `grid`'s children out as a masonry from now on. */
export function masonry(grid) {
  grid.classList.add('masonry');
  let frame = 0;

  function layout() {
    frame = 0;
    const width = grid.clientWidth;
    if (!width) return; // Not on screen: laid out when it is (the grid's own resize).
    const cards = [...grid.children];
    const { n, gap } = columnsFor(width);
    const col = (width - gap * (n - 1)) / n;
    grid.style.setProperty('--col', col + 'px');
    const heights = cards.map(c => c.offsetHeight); // One layout for all of them.
    const tops = new Array(n).fill(0);
    cards.forEach((c, i) => {
      let shortest = 0;
      for (let k = 1; k < n; k++) if (tops[k] < tops[shortest] - 0.5) shortest = k;
      const left = shortest * (col + gap) + 'px', top = tops[shortest] + 'px';
      if (c.style.left !== left) c.style.left = left;
      if (c.style.top !== top) c.style.top = top;
      tops[shortest] += heights[i] + gap;
    });
    grid.style.height = Math.max(0, Math.max(...tops) - gap) + 'px';
  }

  const later = () => { if (!frame) frame = requestAnimationFrame(layout); };
  const sizes = new ResizeObserver(later);
  sizes.observe(grid);
  const watch = cards => { for (const c of cards) if (c.nodeType === 1) sizes.observe(c); };
  watch(grid.children);
  // New cards: placed before they're ever painted (a mutation callback runs before the next frame).
  new MutationObserver(records => {
    for (const r of records) {
      watch(r.addedNodes);
      for (const gone of r.removedNodes) if (gone.nodeType === 1) sizes.unobserve(gone);
    }
    if (frame) { cancelAnimationFrame(frame); frame = 0; }
    layout();
  }).observe(grid, { childList: true });
  return { layout };
}
