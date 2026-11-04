let accountGeneration=0,sessionGeneration=0,actionGeneration=0,sessionAccount=null,limitMember=null;
function administrationError(error){$('administrationError').textContent=error.message;$('administrationError').hidden=false;}
async function refreshAccounts(){
  const generation=++accountGeneration;$('administrationError').hidden=true;
  try{const rows=await api('/admin/accounts?search='+encodeURIComponent($('accountSearch').value.trim()));if(generation!==accountGeneration)return;$('accountRows').replaceChildren();
    for(const account of rows){const row=document.createElement('tr');[account.display_name+' (@'+account.username+')',account.role,account.member_active===false?'Revoked':account.access_state||'No key request',account.key_prefix||'—'].forEach(value=>cell(row,value));const action=document.createElement('td'),button=document.createElement('button');button.textContent='View sessions';button.onclick=()=>showSessions(account);action.append(button);row.append(action);$('accountRows').append(row);}
    if(!rows.length){const row=document.createElement('tr'),td=document.createElement('td');td.colSpan=5;td.textContent='No matching accounts.';row.append(td);$('accountRows').append(row);}
  }catch(error){if(generation===accountGeneration)administrationError(error);}
}
async function showSessions(account){
  const generation=++sessionGeneration;sessionAccount=account;$('sessionPanel').hidden=false;$('sessionAccount').textContent='Sessions · @'+account.username;$('sessionRows').replaceChildren();$('revokeAllSessions').disabled=true;
  try{const rows=await api('/admin/accounts/'+encodeURIComponent(account.id)+'/sessions');if(generation!==sessionGeneration)return;
    for(const session of rows){const row=document.createElement('tr');[new Date(session.lastSeenAt).toLocaleString(),new Date(session.expiresAt).toLocaleString(),session.active?'Active':'Expired / idle'].forEach(value=>cell(row,value));const action=document.createElement('td'),button=document.createElement('button');button.textContent='Sign out session';button.onclick=async()=>{button.disabled=true;try{await api('/admin/accounts/'+encodeURIComponent(account.id)+'/sessions/'+encodeURIComponent(session.sessionRef),{method:'DELETE'});if(generation===sessionGeneration)showSessions(account);refreshActions();}catch(error){administrationError(error);button.disabled=false;}};action.append(button);row.append(action);$('sessionRows').append(row);}
    $('revokeAllSessions').disabled=!rows.length;if(!rows.length){const row=document.createElement('tr'),td=document.createElement('td');td.colSpan=4;td.textContent='No stored sessions for this account.';row.append(td);$('sessionRows').append(row);}
  }catch(error){if(generation===sessionGeneration)administrationError(error);}
}
async function refreshActions(){
  const generation=++actionGeneration;
  try{const rows=await api('/admin/action-log?limit=50&action='+encodeURIComponent($('actionFilter').value.trim()));if(generation!==actionGeneration)return;
    populateTable('actionRows',rows,[r=>new Date(r.occurred_at).toLocaleString(),r=>r.actor_id,r=>r.action,r=>r.target_id,r=>r.details_json]);
  }catch(error){if(generation===actionGeneration)administrationError(error);}
}
function editMemberLimits(member){limitMember=member;$('limitEditor').hidden=false;$('limitMember').textContent=member.name+' · '+member.keyPrefix;$('editRpm').value=member.rpm;$('editConcurrency').value=member.concurrency;$('limitMessage').textContent='';$('saveLimits').disabled=false;$('editRpm').focus();}
$('limitForm').onsubmit=async event=>{event.preventDefault();if(!limitMember||$('saveLimits').disabled)return;const member=limitMember;$('saveLimits').disabled=true;
  try{await api('/admin/members/'+encodeURIComponent(member.id)+'/limits',{method:'PATCH',body:JSON.stringify({rpm:Number($('editRpm').value),concurrency:Number($('editConcurrency').value),expectedRpm:member.rpm,expectedConcurrency:member.concurrency})});$('limitMessage').textContent='Limits updated. Refreshing member list…';await refresh();$('limitEditor').hidden=true;limitMember=null;}
  catch(error){$('limitMessage').textContent=error.message+' If another change occurred, reopen Edit limits after refreshing.';}
  finally{$('saveLimits').disabled=false;}
};
$('closeLimits').onclick=()=>{$('limitEditor').hidden=true;limitMember=null;};
$('revokeAllSessions').onclick=async()=>{if(!sessionAccount)return;const account=sessionAccount,generation=sessionGeneration;$('revokeAllSessions').disabled=true;try{await api('/admin/accounts/'+encodeURIComponent(account.id)+'/sessions',{method:'DELETE'});if(generation===sessionGeneration)showSessions(account);refreshActions();}catch(error){administrationError(error);$('revokeAllSessions').disabled=false;}};
$('refreshAccounts').onclick=()=>{refreshAccounts();refreshActions();};
$('accountSearchForm').onsubmit=event=>{event.preventDefault();refreshAccounts();};
$('actionSearchForm').onsubmit=event=>{event.preventDefault();refreshActions();};
function loadAdministrationPage(){if(location.hash==='#view/accounts'&&!$('content').hidden){refreshAccounts();refreshActions();}}
addEventListener('hashchange',loadAdministrationPage);addEventListener('meter:admin-ready',loadAdministrationPage);
