// The apps' PictureLineTest, case for case: node app/tests/picline.test.mjs
import assert from 'node:assert/strict';
import { parse, format } from '../js/picline.js';

const cases = {
  aPlainLine() { assert.deepEqual(parse('![[IMG_1_ab.png]]'), { name: 'IMG_1_ab.png', width: 0, align: 'left' }); },
  aWidth() { assert.deepEqual(parse('![[IMG_1_ab.png|60%]]'), { name: 'IMG_1_ab.png', width: 60, align: 'left' }); },
  aWidthAndPlace() { assert.deepEqual(parse('  ![[IMG_1_ab.png|35%|center]] '), { name: 'IMG_1_ab.png', width: 35, align: 'center' }); },
  aPlaceAlone() { assert.deepEqual(parse('![[IMG_1_ab.png|right]]'), { name: 'IMG_1_ab.png', width: 0, align: 'right' }); },
  widthsSnapAndStayInRange() {
    assert.equal(parse('![[a.png|62%]]').width, 60);
    assert.equal(parse('![[a.png|5%]]').width, 0);
    assert.equal(parse('![[a.png|150%]]').width, 0);
  },
  junkIsIgnored() { assert.deepEqual(parse('![[a.png|wide|300]]'), { name: 'a.png', width: 0, align: 'left' }); },
  notPictures() {
    assert.equal(parse('[[A note]]'), null);
    assert.equal(parse('![[a/b.png]]'), null);
    assert.equal(parse('text ![[a.png]]'), null);
  },
  formatWritesOnlyWhatDiffers() {
    assert.equal(format({ name: 'a.png' }), '![[a.png]]');
    assert.equal(format({ name: 'a.png', width: 60, align: 'left' }), '![[a.png|60%]]');
    assert.equal(format({ name: 'a.png', width: 62, align: 'center' }), '![[a.png|60%|center]]');
    assert.equal(format({ name: 'a.png', width: 0, align: 'right' }), '![[a.png|right]]');
  },
  roundTrip() {
    for (const l of ['![[a.png]]', '![[a.png|60%]]', '![[a.png|25%|right]]', '![[a.png|center]]']) assert.equal(format(parse(l)), l);
  },
};
let failed = 0;
for (const [name, f] of Object.entries(cases)) {
  try { f(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, e.message); }
}
process.exit(failed ? 1 : 0);
