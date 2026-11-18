(function(page) {
    'use strict';
    const sheet = document.createElement('link');
    sheet.rel='stylesheet'; sheet.href=page.stylesheet; sheet.referrerPolicy='no-referrer';
    const waiting=[];
    sheet.onload=()=>{const pending=waiting.splice(0);add(pending);};
    function add(selectors) {
        if (!sheet.sheet) { if(waiting.length<16384)waiting.push(...selectors); return; }
        for (const selector of selectors) {
            try { sheet.sheet.insertRule(selector + '{display:none!important}', sheet.sheet.cssRules.length); } catch (_) {}
        }
    }
    const rules = page.procedural_actions.map(s => JSON.parse(s));
    function matcher(s) {
        const end = s.lastIndexOf('/');
        if (s[0] === '/' && end > 0) return new RegExp(s.slice(1,end), s.slice(end+1));
        return {test: value => value.includes(s)};
    }
    function select(nodes, operator) {
        const a = operator.arg, type = operator.type;
        if (type === 'css-selector') return nodes.flatMap(n => Array.from(n.querySelectorAll(a)));
        if (type === 'has-text') { const m = matcher(a); return nodes.filter(n => m.test(n.textContent)); }
        if (type === 'matches-path') return matcher(a).test(location.pathname + location.search) ? nodes : [];
        if (type === 'min-text-length') return nodes.filter(n => n.textContent.length >= Number(a));
        if (type === 'upward') return nodes.map(n => { if (!/^\d+$/.test(a)) return n.parentElement?.closest(a); for (let i=Number(a); i>0 && n; i--) n=n.parentElement; return n; }).filter(Boolean);
        if (type === 'xpath') return nodes.flatMap(n => { const r=document.evaluate(a,n,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null); return Array.from({length:r.snapshotLength}, (_,i)=>r.snapshotItem(i)).filter(n=>n?.nodeType===1); });
        if (type === 'matches-attr') {
            const sep=a.indexOf('='); const name=matcher((sep<0?a:a.slice(0,sep)).trim().replace(/^['"]|['"]$/g,''));
            const value=sep<0?null:matcher(a.slice(sep+1).trim().replace(/^['"]|['"]$/g,''));
            return nodes.filter(n => n.getAttributeNames().some(k=>name.test(k)&&(!value||value.test(n.getAttribute(k)))));
        }
        if (['matches-css','matches-css-before','matches-css-after'].includes(type)) {
            const sep=a.indexOf(':'); if(sep<1) return [];
            const property=a.slice(0,sep).trim(); const test=matcher(a.slice(sep+1).trim());
            const pseudo=type==='matches-css'?null:'::'+type.split('-')[2];
            return nodes.filter(n=>test.test(getComputedStyle(n,pseudo).getPropertyValue(property)));
        }
        return [];
    }
    const bridge=globalThis.PeachyGeneric;
    let pending=false;
    const seenClasses=new Set(), seenIds=new Set();
    function scan() {
        pending=false;
        if(!document.documentElement) return;
        if(!sheet.isConnected) (document.head||document.documentElement).appendChild(sheet);
        for(const rule of rules) { try {
            let nodes=[document]; for(const op of rule.selector) nodes=select(nodes,op);
            for(const n of nodes) {
                if(n.nodeType!==1) continue;
                const a=rule.action;
                if (!a) { if(n.style.getPropertyValue('display')!=='none'||n.style.getPropertyPriority('display')!=='important') n.style.setProperty('display','none','important'); }
                else if (a.type==='remove') n.remove();
                else if (a.type==='remove-class') n.classList.remove(a.arg);
                else if (a.type==='remove-attr') n.removeAttribute(a.arg);
                else if (a.type==='style') { const style=document.createElement('span').style; style.cssText=a.arg; for(const prop of style) if(n.style.getPropertyValue(prop)!==style.getPropertyValue(prop)||n.style.getPropertyPriority(prop)!==style.getPropertyPriority(prop)) n.style.setProperty(prop,style.getPropertyValue(prop),style.getPropertyPriority(prop)); }
            }
        } catch (_) {} }
        if (bridge && !page.generichide) {
            let c=[],i=[]; const send=()=>{if(c.length+i.length)bridge.postMessage(JSON.stringify([c,i]));c=[];i=[];};
            for(const n of document.querySelectorAll('[id],[class]')) {
                for(const k of n.classList) if(k.length<=128 && !seenClasses.has(k) && seenClasses.size<8192){seenClasses.add(k);c.push(k);if(c.length+i.length>=96)send();}
                if(n.id && n.id.length<=128 && !seenIds.has(n.id) && seenIds.size<8192){seenIds.add(n.id);i.push(n.id);if(c.length+i.length>=96)send();}
            } send();
        }
    }
    const schedule=()=>{ if(!pending){pending=true;setTimeout(scan,50);} };
    if(bridge) bridge.onmessage=e=>{try{add(JSON.parse(e.data));}catch(_){}};
    const observer=new MutationObserver(schedule);observer.observe(document,{subtree:true,childList:true,attributes:true,characterData:rules.length>0});
    scan();document.addEventListener('DOMContentLoaded',scan,{once:true});
})