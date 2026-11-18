// Maintainer step, not an app/network-startup dependency. Uses only immutable upstream Git objects.
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
const pin = '765c51daa13143b5a7cbf60be4633fb8e7d324a5';
const owner = 'gorhill/uBlock';
const base = new URL('../../app/src/main/assets/adblock/', import.meta.url);
const tree = await (await fetch(`https://api.github.com/repos/${owner}/git/trees/${pin}?recursive=1`)).json();
if (tree.sha !== pin || tree.truncated) throw Error('Unverified source tree');
const entries = new Map(tree.tree.map(x => [x.path, x]));
async function read(name) {
    const r = await fetch(`https://raw.githubusercontent.com/${owner}/${pin}/${name}`);
    if (!r.ok) throw Error(`${r.status}: ${name}`);
    const bytes = Buffer.from(await r.arrayBuffer());
    const id = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
    if (entries.get(name)?.sha !== id) throw Error(`Bad blob: ${name}`);
    return bytes;
}
const context = vm.createContext(Object.create(null), { codeGeneration: { strings: false, wasm: false } });
const modules = new Map();
const names = [...entries.keys()].filter(n => /^src\/js\/resources\/[\w-]+\.js$/.test(n));
names.push(...['urlskip','arglist-parser','jsonpath'].map(n => `src/js/${n}.js`));
await Promise.all(names.map(async name => {
    const source = (await read(name)).toString();
    if (!source.slice(0, 1600).includes('GNU General Public License')) throw Error(`Missing licence: ${name}`);
    modules.set(name, new vm.SourceTextModule(source, { identifier: name, context }));
}));
const entry = modules.get('src/js/resources/scriptlets.js');
await entry.link((id, parent) => {
    const mod = modules.get(path.posix.join(path.posix.dirname(parent.identifier), id));
    if (!mod) throw Error(`Unknown module: ${id}`);
    return mod;
});
await entry.evaluate({ timeout: 3000 }); // registers definitions; does not run a scriptlet against a page
const rows = entry.namespace.builtinScriptlets.map(s => ({
    name: s.name, aliases: s.aliases ?? [], kind: { mime: s.name.endsWith('.fn') ? 'fn/javascript' : 'application/javascript' },
    content: Buffer.from(Function.prototype.toString.call(s.fn)).toString('base64'),
    dependencies: s.dependencies ?? [], permission: s.requiresTrust === true ? 1 : 0,
}));
// Only inert replacements: never turn a remote filter argument into a network redirect.
for (const [name, mime, data, aliases] of [
    ['noop.js', 'application/javascript', ';', []], ['noop.css', 'text/css', '', []],
    ['noop.txt', 'text/plain', '', ['empty']], ['noop.html', 'text/html', '<!doctype html>', []],
    ['noop.json', 'application/json', '{}', []],
]) rows.push({name, aliases, kind:{mime}, content:Buffer.from(data).toString('base64')});
await mkdir(base, { recursive:true });
await writeFile(new URL('resources.json', base), JSON.stringify(rows));
await writeFile(new URL('COPYING-uBlock.txt', base), await read('LICENSE.txt'));
console.log(`${rows.length} resources; source ${owner}@${pin}; ${fileURLToPath(base)}`);
