package com.ayuemin.ilmar.browser

import org.json.JSONObject

/** Fixed DOM commands, not arbitrary model JavaScript. Credentials and high-impact submits go to the user. */
internal object BrowserActions {
    fun script(command: String, args: JSONObject): String {
        require(command in setOf("click", "type", "select"))
        require(args.optString("page_id").isNotBlank())
        if (command == "click" && (args.has("x") || args.has("y"))) require(args.has("x") && args.has("y") && args.getInt("x") in 0..1000 && args.getInt("y") in 0..1000)
        else require(args.optInt("ref") > 0)
        require(args.optString("text").length <= 8000)
        return "(function(){const command=" + JSONObject.quote(command) + ";const args=" + args.toString() + ";" + BODY + "})()"
    }
    const val BODY = """
      const state=window.__ilmarBrowser;
      if(!state || state.id!==args.page_id)return JSON.stringify({error:'STALE_PAGE',action_performed:false});
      const coordinate=command==='click' && Number.isInteger(args.x) && Number.isInteger(args.y);
      const cx=Math.min(innerWidth-1,innerWidth*args.x/1000),cy=Math.min(innerHeight-1,innerHeight*args.y/1000);
      const hit=coordinate?document.elementFromPoint(cx,cy):state.elements.get(args.ref);
      const el=hit?.closest?.('button,a,input,textarea,select,[role="button"]')||hit;
      if(!el || !el.isConnected)return JSON.stringify({error:'STALE_ELEMENT',action_performed:false});
      const sensitive=e=>e.matches('input[type=password],input[autocomplete="one-time-code"],input[autocomplete*="password"],input[autocomplete^="cc-"]');
      const label=((el.innerText||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.value||'')).toLowerCase();
      const risky=/\b(pay|purchase|checkout|delete|transfer)\b|оплат|купить|удалить|перевести/.test(label);
      const credentialForm=el.form && Array.from(el.form.querySelectorAll('input')).some(sensitive);
      if(sensitive(el) || risky || (credentialForm && (command==='click' || args.submit)) || el.type==='file')
        return JSON.stringify({error:'USER_REQUIRED',action_performed:false,_awaiting_user:true,message:'Нужен ввод или подтверждение пользователя в браузере.'});
      el.scrollIntoView({block:'center'});
      if(command==='click'){
        if(el.tagName==='A' && el.href && !el.href.startsWith('https://'))return JSON.stringify({error:'UNSUPPORTED_LINK',action_performed:false});
        if(coordinate){
          const options={bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window};
          if(window.PointerEvent){el.dispatchEvent(new PointerEvent('pointerdown',options));el.dispatchEvent(new PointerEvent('pointerup',options));}
          for(const name of ['mousedown','mouseup','click'])el.dispatchEvent(new MouseEvent(name,options));
        }else el.click();
      } else if(command==='type'){
        if(!el.matches('input,textarea,[contenteditable="true"]'))return JSON.stringify({error:'NOT_EDITABLE',action_performed:false});
        el.focus();
        if(el.isContentEditable)el.textContent=args.text;
        else {
          const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
          Object.getOwnPropertyDescriptor(proto,'value').set.call(el,args.text);
        }
        el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
        if(args.submit){
          if(!el.form)return JSON.stringify({error:'NO_FORM',action_performed:true});
          el.form.requestSubmit?el.form.requestSubmit():el.form.submit();
        }
      } else {
        if(el.tagName!=='SELECT')return JSON.stringify({error:'NOT_SELECT',action_performed:false});
        if(!Array.from(el.options).some(o=>o.value===args.text))return JSON.stringify({error:'UNKNOWN_OPTION',action_performed:false});
        el.value=args.text;el.dispatchEvent(new Event('change',{bubbles:true}));
      }
      return JSON.stringify({action:command,action_performed:true,external_result:'Check the next page snapshot; a click does not prove completion.'});
    """
}
