package com.ayuemin.ilmar.browser

import org.json.JSONObject

/** Fixed text lookup, never model-provided JavaScript. Ambiguous matches require another decision. */
internal object BrowserFind {
    fun script(query: String, exact: Boolean, policy: BrowserPolicy): String {
        require(query.isNotBlank() && query.length <= 300) { "Поиск элемента: 1–300 символов" }
        return "(function(){const query=" + JSONObject.quote(query) + ";const exact=" + exact + ";const baseline=JSON.parse(" + BrowserSnapshot.script(false, policy) + ");" + BODY + "})()"
    }
    const val BODY = """
      if(baseline.requires_user)return JSON.stringify(baseline);
      const state=window.__ilmarBrowser;
      const normalize=s=>s.replace(/\s+/g,' ').trim().toLowerCase();
      const label=el=>(el.getAttribute('aria-label')||el.getAttribute('placeholder')||Array.from(el.labels||[]).map(l=>l.innerText).join(' ')||el.innerText||'').trim();
      const wanted=normalize(query);
      const all=Array.from(document.querySelectorAll('a[href],button,input,textarea,select,[role="button"],[contenteditable="true"]'));
      const candidates=all.slice(0,5000)
        .filter(el=>el.getClientRects().length && (exact?normalize(label(el))===wanted:normalize(label(el)).includes(wanted)));
      const matches=candidates.slice(0,20).map(el=>{
        if(!state.refs.has(el))state.refs.set(el,state.next++);
        const ref=state.refs.get(el);state.elements.set(ref,el);
        return {ref:ref,tag:el.tagName.toLowerCase(),type:el.type||'',label:label(el).slice(0,200),url:el.tagName==='A' && /^https:\/\//i.test(el.href||'') && !/^[^?#]*@/.test((el.href||'').replace(/^https:\/\//i,''))?el.href:undefined,
          sensitive:el.matches('input[type=password],input[autocomplete="one-time-code"],input[autocomplete*="password"],input[autocomplete^="cc-"]')};
      });
      return JSON.stringify({url:baseline.url,title:baseline.title,page_id:state.id,representation:'DOM_FIND',query:query,matches:matches,
        matches_truncated:candidates.length>20||all.length>5000,untrusted:true,notice:'Use only a unique matching ref. No values of input fields are included.'});
    """
}
