// App-styled select control with keyboard navigation and an ordinary change event.
document.querySelectorAll('[data-dropdown]').forEach(root=>{
  const input=root.querySelector('input'),trigger=root.querySelector('.dropdown-trigger');
  const label=root.querySelector('[data-dropdown-value]'),menu=root.querySelector('[role=listbox]');
  const options=[...menu.querySelectorAll('[role=option]')];
  let search='',lastKey=0;
  const selected=()=>Math.max(0,options.findIndex(option=>option.dataset.value===input.value));
  function close(restore=false){menu.hidden=true;trigger.setAttribute('aria-expanded','false');if(restore)trigger.focus();}
  function open(index=selected()){menu.hidden=false;trigger.setAttribute('aria-expanded','true');options[index].focus();}
  function choose(option){
    const changed=input.value!==option.dataset.value;
    input.value=option.dataset.value;label.textContent=option.textContent;
    options.forEach(item=>item.setAttribute('aria-selected',String(item===option)));
    close(true);if(changed)input.dispatchEvent(new Event('change',{bubbles:true}));
  }
  trigger.addEventListener('click',()=>menu.hidden?open():close());
  options.forEach(option=>option.addEventListener('click',()=>choose(option)));
  root.addEventListener('keydown',event=>{
    let index=options.indexOf(document.activeElement);
    if(event.key==='Escape'){event.preventDefault();close(true);return;}
    if(event.key==='Tab'){if(!menu.hidden)close(true);return;}
    if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){
      event.preventDefault();
      if(event.key==='Home')index=0;
      else if(event.key==='End')index=options.length-1;
      else index=index<0?selected():(index+(event.key==='ArrowDown'?1:-1)+options.length)%options.length;
      open(index);return;
    }
    if((event.key==='Enter'||event.key===' ')&&index>=0){event.preventDefault();choose(options[index]);return;}
    if(event.key.length===1&&!event.ctrlKey&&!event.metaKey&&!event.altKey&&event.key!==' '){
      event.preventDefault();const now=Date.now();search=(now-lastKey<700?search:'')+event.key.toLowerCase();lastKey=now;
      const match=options.findIndex(option=>option.textContent.toLowerCase().startsWith(search));
      if(match>=0)open(match);
    }
  });
  document.addEventListener('pointerdown',event=>{if(!root.contains(event.target))close();});
  root.addEventListener('focusout',event=>{if(!root.contains(event.relatedTarget))close();});
});
