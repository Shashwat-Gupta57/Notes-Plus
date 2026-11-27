// The apps' UltimateTest, case for case: node app/tests/ultimate.test.mjs
import assert from 'node:assert/strict';
import { blocks, blocksJson, blocksContent, mapHeight, dayWords, indentOf, insideSummary, mindMap, mindMapJson } from '../js/model.js';

const MIXED = '{"blocks":[{"id":"a","kind":"text","md":"Hello **you**\\n![[IMG_1_ab.png|60%]]"},'
  + '{"id":"b","kind":"list","items":[{"text":"Milk","done":true},{"text":" ","done":false},{"text":"Eggs","done":false}]},'
  + '{"id":"c","kind":"mindmap","nodes":[{"id":1,"parent":-1,"text":"Me","x":0,"y":0,"sub":"ideal"},{"id":2,"parent":1,"text":"Calm","x":230,"y":0}],"links":[],"h":400},'
  + '{"id":"d","kind":"audio","recordings":[{"file":"rec_1.wav","label":"","duration":1000,"createdAt":1,"wave":[0.5]},{"file":"rec_2.wav","label":"Walk","duration":1,"createdAt":2,"wave":[]}]},'
  + '{"id":"e","kind":"sketch","x":1}]}';

const INSIDE = '{"blocks":[{"id":"m","kind":"mindmap","nodes":[{"id":1,"parent":-1,"text":"Me","x":0,"y":0,"blocks":['
  + '{"kind":"list","items":[{"text":"Breathe","done":true},{"text":"Walk","done":false}]},'
  + '{"kind":"audio","recordings":[{"file":"r.wav","label":"Om","duration":1,"createdAt":1,"wave":[]}]}]},'
  + '{"id":2,"parent":1,"text":"Calm","x":1,"y":1}],"links":[]}]}';

const cases = {
  insideAnIdea() {
    const b = blocks(INSIDE);
    assert.equal(blocksContent(b), '- Me\n- [x] Breathe\n- [ ] Walk\n🎙 Om\n- Calm');
    const map = mindMap(JSON.stringify(b[0]));
    assert.equal(insideSummary(blocks(JSON.stringify({ blocks: map.nodes[0].blocks }))), '☑ 1/2 · 🎙 1');
    assert.equal(map.nodes[1].blocks, null);
    assert.equal(JSON.parse(mindMapJson(map)).nodes[0].blocks.length, 2);
  },
  indents() {
    assert.equal(indentOf({}), 0);
    assert.equal(indentOf({ indent: 2 }), 2);
    assert.equal(indentOf({ indent: 9 }), 4);
    assert.equal(indentOf({ indent: -1 }), 0);
    assert.equal(indentOf({ indent: 'x' }), 0);
    assert.equal(blocks(blocksJson([{ id: 'a', kind: 'list', indent: 2, items: [] }]))[0].indent, 2);
  },
  everyKindsWordsInOrder() {
    assert.equal(blocksContent(blocks(MIXED)), 'Hello **you**\n![[IMG_1_ab.png|60%]]\n- [x] Milk\n- [ ] Eggs\n- Me\n- ideal\n- Calm\n🎙 Recording\n🎙 Walk');
  },
  unknownKindsAreKept() {
    const b = blocks(blocksJson(blocks(MIXED)));
    assert.equal(b.length, 5);
    assert.equal(b[4].kind, 'sketch');
    assert.equal(b[4].x, 1);
    assert.equal(b[2].h, 400);
  },
  junkIsSkipped() {
    const b = blocks('{"blocks":[1,null,{"kind":5},{"id":"","kind":"text","md":"  "}]}');
    assert.equal(b.length, 1);
    assert.equal(b[0].kind, 'text');
    assert.ok(b[0].id.length > 0);
    assert.equal(blocksContent(b), '');
  },
  notJson() { assert.equal(blocks('oops').length, 0); assert.equal(blocks(null).length, 0); },
  mapHeights() {
    assert.equal(mapHeight({}), 320);
    assert.equal(mapHeight({ h: 50 }), 160);
    assert.equal(mapHeight({ h: 5000 }), 1200);
    assert.equal(mapHeight({ h: 'abc' }), 320);
    assert.equal(mapHeight({ h: 333.6 }), 334);
  },
  aDay() {
    const day = new Date(2026, 10, 26).getTime();
    assert.equal(dayWords(day), 'Thursday, 26 November 2026');
    assert.equal(blocksContent([{ kind: 'day', day, mood: '🙂' }, { kind: 'day', day, mood: '' }]),
      '### Thursday, 26 November 2026 🙂\n### Thursday, 26 November 2026');
  },
};

let failed = 0;
for (const [name, fn] of Object.entries(cases)) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, e.message); }
}
process.exit(failed ? 1 : 0);
